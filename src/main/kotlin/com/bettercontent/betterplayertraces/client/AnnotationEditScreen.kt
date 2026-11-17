package com.bettercontent.betterplayertraces.client

import com.bettercontent.betterplayertraces.client.death.DeathEchoRecorder
import com.bettercontent.betterplayertraces.domain.AnnotationComponents
import com.bettercontent.betterplayertraces.domain.EchoMutation
import com.bettercontent.betterplayertraces.dto.VisibleAnnotationDto
import com.bettercontent.betterplayertraces.network.AnnotationCreatePacket
import com.bettercontent.betterplayertraces.network.AnnotationDeletePacket
import com.bettercontent.betterplayertraces.network.AnnotationMutationResultPacket
import com.bettercontent.betterplayertraces.network.AnnotationUpdatePacket
import com.bettercontent.betterplayertraces.network.TracesNetwork
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import java.util.UUID

data class AnnotationDraft(
    val target: BlockPos,
    val annotation: VisibleAnnotationDto?,
    var text: String = annotation?.text.orEmpty(),
    var icon: String = annotation?.icon.orEmpty(),
    var color: Int = annotation?.color ?: 0,
    var gesture: GestureChoice = if (annotation?.hasEcho == true) GestureChoice.Keep else GestureChoice.None,
    val recentClip: ByteArray? = null,
    var error: String = "",
    var publication: DraftPublication = DraftPublication.EDITING,
    var originSession: UUID? = null,
    var originDimension: String? = null,
)

enum class DraftPublication { EDITING, PENDING, INDETERMINATE }

/** An editing draft may have only ONE active request identity; retire definite old outcomes before retry. */
internal fun <T> retireDraftAliases(entries: MutableMap<UUID, T>, draft: AnnotationDraft, owner: (T) -> AnnotationDraft) {
    entries.entries.removeIf { owner(it.value) === draft }
}

internal fun sameNoteCommandScope(entrySession: UUID, entryDimension: String, session: UUID, dimension: String): Boolean =
    entrySession == session && entryDimension == dimension

internal fun noteDraftHasRoom(otherEntries: Int, projectedBytes: Long): Boolean =
    otherEntries < 64 && projectedBytes <= 8L * 1024L * 1024L

sealed interface GestureChoice {
    data object None : GestureChoice
    data object Keep : GestureChoice
    data object Recent : GestureChoice
    data object Remove : GestureChoice
    data class Quark(val id: String) : GestureChoice
}

object AnnotationDrafts {
    private data class Entry(val draft: AnnotationDraft, val session: UUID, val dimension: String)
    private val retained = LinkedHashMap<UUID, Entry>()
    private var session = UUID.randomUUID()
    private var dimension = ""
    private fun draftBytes(draft: AnnotationDraft): Long =
        (draft.recentClip?.size ?: 0).toLong() + draft.text.toByteArray(Charsets.UTF_8).size + 256L

    /** Command scope is connection + dimension, independent of query radius/subscription generation. */
    fun observeContext() {
        val current = Minecraft.getInstance().level?.dimension()?.location()?.toString().orEmpty()
        if (current != dimension) {
            if (dimension.isNotEmpty()) retained.values.filter { it.draft.publication == DraftPublication.PENDING }
                .forEach { it.draft.publication = DraftPublication.INDETERMINATE }
            session = UUID.randomUUID()
            dimension = current
        }
    }

    fun disconnect() {
        retained.values.filter { it.draft.publication == DraftPublication.PENDING }
            .forEach { it.draft.publication = DraftPublication.INDETERMINATE }
        session = UUID.randomUUID()
        dimension = ""
    }

    fun retainedAt(target: BlockPos): AnnotationDraft? {
        observeContext()
        return retained.values.lastOrNull { sameNoteCommandScope(it.session, it.dimension, session, dimension) &&
            it.draft.target == target }?.draft
    }

    fun currentDimension(): String { observeContext(); return dimension }

    fun bindDraft(draft: AnnotationDraft) {
        observeContext()
        if (draft.originSession != null || dimension.isEmpty()) return
        draft.originSession = session
        draft.originDimension = dimension
        if (noteDraftHasRoom(retained.size, retained.values.sumOf { draftBytes(it.draft) } + draftBytes(draft)))
            retained[UUID.randomUUID()] = Entry(draft, session, dimension)
        else draft.error = "Draft ledger full; dismiss old drafts before editing"
    }

    /** Previous-connection entries are read-only, never reused as a current-world mutation. */
    fun previousSessionDraft(): AnnotationDraft? {
        observeContext()
        return retained.values.firstOrNull { it.session != session }?.draft
    }

    fun isPreviousSession(draft: AnnotationDraft): Boolean =
        draft.originSession?.let { it != session || draft.originDimension != dimension } == true ||
            retained.values.any { it.draft === draft && it.session != session }

    fun dismissPreviousSession(draft: AnnotationDraft) {
        observeContext()
        retained.entries.removeIf { it.value.draft === draft && it.value.session != session }
    }

    fun pendingDimension(requestId: UUID, sessionId: UUID): String? {
        observeContext()
        return retained[requestId]?.takeIf { it.session == session && sessionId == session &&
            it.dimension == dimension && it.draft.publication == DraftPublication.PENDING }?.dimension
    }

    fun submit(requestId: UUID, draft: AnnotationDraft): UUID? {
        observeContext()
        val others = retained.values.filter { it.draft !== draft }
        val projectedBytes = others.sumOf { draftBytes(it.draft) } + draftBytes(draft)
        if (dimension.isEmpty() || draft.publication != DraftPublication.EDITING || isPreviousSession(draft) ||
            !noteDraftHasRoom(others.size, projectedBytes)) {
            draft.error = "Draft ledger full or unavailable; dismiss old read-only drafts to make room"
            return null
        }
        retireDraftAliases(retained, draft) { it.draft }
        draft.error = ""
        draft.publication = DraftPublication.PENDING
        retained[requestId] = Entry(draft, session, dimension)
        return session
    }

    fun failLocal(requestId: UUID, error: Throwable) {
        observeContext()
        val entry = retained[requestId] ?: return
        if (entry.session != session || entry.dimension != dimension) return
        entry.draft.publication = DraftPublication.EDITING
        entry.draft.error = error.message ?: "gesture capture failed"
    }

    fun markTransportUnknown(requestId: UUID) {
        retained[requestId]?.draft?.let {
            it.publication = DraftPublication.INDETERMINATE
            it.error = "Send outcome unknown; do not submit a new request"
        }
    }

    fun discardEditing(draft: AnnotationDraft) {
        retained.entries.removeIf { it.value.draft === draft && draft.publication == DraftPublication.EDITING &&
            sameNoteCommandScope(it.value.session, it.value.dimension, session, dimension) }
    }

    fun accept(result: AnnotationMutationResultPacket) {
        observeContext()
        val entry = retained[result.requestId] ?: return
        if (!sameNoteCommandScope(entry.session, entry.dimension, session, dimension) ||
            entry.session != result.sessionId || entry.dimension != result.dimension) return
        val draft = entry.draft
        when (result.status) {
            com.bettercontent.betterplayertraces.network.NoteWireStatus.PENDING -> Unit
            com.bettercontent.betterplayertraces.network.NoteWireStatus.INDETERMINATE -> {
                draft.publication = DraftPublication.INDETERMINATE
                draft.error = "Publication unknown; keep this draft. Do not send a new request."
            }
            com.bettercontent.betterplayertraces.network.NoteWireStatus.COMMITTED -> {
                retained.remove(result.requestId)
                Minecraft.getInstance().player?.displayClientMessage(Component.literal(
                    if (result.deleted) "Annotation deleted" else "Annotation saved"), true)
                if ((Minecraft.getInstance().screen as? AnnotationEditScreen)?.draft === draft)
                    Minecraft.getInstance().setScreen(null)
                TracesNetwork.requestNearby(2)
            }
            com.bettercontent.betterplayertraces.network.NoteWireStatus.REJECTED -> {
                draft.publication = DraftPublication.EDITING
                draft.error = when (result.reason) {
                    com.bettercontent.betterplayertraces.storage.NoteRejectReason.REVISION_CONFLICT -> "Note changed; reopen before editing"
                    com.bettercontent.betterplayertraces.storage.NoteRejectReason.BUSY,
                    com.bettercontent.betterplayertraces.storage.NoteRejectReason.TARGET_BUSY -> "Note is busy; wait for the prior request"
                    com.bettercontent.betterplayertraces.storage.NoteRejectReason.STORAGE_UNAVAILABLE,
                    com.bettercontent.betterplayertraces.storage.NoteRejectReason.STORAGE_CAPACITY -> "Storage unavailable; draft retained"
                    else -> result.reason?.name?.lowercase()?.replace('_', ' ') ?: "Note rejected"
                }
                Minecraft.getInstance().player?.displayClientMessage(Component.literal("Note rejected; draft retained. Reopen to edit."), true)
            }
        }
    }
}

internal class OpeningKeyInputGuard(openingKeyCode: Int?) {
    private var openingKeyCode = openingKeyCode

    fun suppressCharacterInput(): Boolean = openingKeyCode != null

    fun onKeyReleased(keyCode: Int) {
        if (keyCode == openingKeyCode) openingKeyCode = null
    }
}

class AnnotationEditScreen(internal val draft: AnnotationDraft, openingKeyCode: Int? = null) :
    Screen(Component.literal(if (draft.annotation == null) "New Trace Note" else "Edit Trace Note")) {
    constructor(
        target: BlockPos,
        annotation: VisibleAnnotationDto?,
        recentClip: ByteArray? = null,
        error: String = "",
        openingKeyCode: Int? = null,
    ) : this(AnnotationDraft(target, annotation, recentClip = recentClip, error = error), openingKeyCode)

    private val openingKeyGuard = OpeningKeyInputGuard(openingKeyCode)
    private lateinit var textBox: EditBox
    private lateinit var saveButton: Button
    private var deleteButton: Button? = null
    private lateinit var iconButton: Button
    private lateinit var colorButton: Button
    private lateinit var gestureButton: Button
    private val icons = listOf("") + AnnotationComponents.icons
    private val colors = AnnotationComponents.colors.entries.toList()
    private val quarkEmotes by lazy { QuarkEmoteBridge.availableEmotes() }

    override fun init() {
        AnnotationDrafts.bindDraft(draft)
        val left = width / 2 - 150
        val top = height / 2 - 70
        textBox = EditBox(font, left, top, 300, 20, Component.literal("Optional note text"))
        textBox.setMaxLength(AnnotationComponents.MAX_TEXT_LENGTH)
        textBox.value = draft.text
        textBox.setResponder { draft.text = it; refreshSave() }
        addRenderableWidget(textBox)

        iconButton = addRenderableWidget(Button.builder(iconLabel()) { cycleIcon() }.bounds(left, top + 28, 96, 20).build())
        colorButton = addRenderableWidget(Button.builder(colorLabel()) { cycleColor() }.bounds(left + 102, top + 28, 96, 20).build())
        gestureButton = addRenderableWidget(Button.builder(gestureLabel()) { cycleGesture() }.bounds(left + 204, top + 28, 96, 20).build())

        saveButton = addRenderableWidget(Button.builder(Component.literal("Save")) { save() }.bounds(left, top + 58, 92, 20).build())
        val editableAnnotation = draft.annotation
        if (editableAnnotation != null) deleteButton = addRenderableWidget(Button.builder(Component.literal("Delete")) {
            val requestId = UUID.randomUUID()
            val sessionId = AnnotationDrafts.submit(requestId, draft) ?: return@builder
            runCatching { TracesNetwork.deleteAnnotation(AnnotationDeletePacket(
                editableAnnotation.id, editableAnnotation.revision, requestId, sessionId, AnnotationDrafts.currentDimension())) }
                .onFailure { AnnotationDrafts.markTransportUnknown(requestId) }
            refreshSave()
        }.bounds(left + 104, top + 58, 92, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Cancel")) {
            AnnotationDrafts.discardEditing(draft)
            onClose()
        }.bounds(left + 208, top + 58, 92, 20).build())
        if (AnnotationDrafts.isPreviousSession(draft)) addRenderableWidget(Button.builder(Component.literal("Dismiss old draft")) {
            AnnotationDrafts.dismissPreviousSession(draft)
            onClose()
        }.bounds(left, top + 86, 196, 20).build())
        setInitialFocus(textBox)
        refreshSave()
    }

    private fun cycleIcon() {
        draft.icon = icons[(icons.indexOf(draft.icon).coerceAtLeast(0) + 1) % icons.size]
        if (draft.icon.isEmpty()) draft.color = 0 else if (draft.color !in AnnotationComponents.colors.values) draft.color = colors.first().value
        iconButton.message = iconLabel(); colorButton.message = colorLabel(); refreshSave()
    }

    private fun cycleColor() {
        if (draft.icon.isEmpty()) return
        val current = colors.indexOfFirst { it.value == draft.color }
        draft.color = colors[(current + 1).coerceAtLeast(0) % colors.size].value
        colorButton.message = colorLabel()
    }

    private fun gestureOptions(): List<GestureChoice> = if (draft.annotation == null) {
        listOf(GestureChoice.None, GestureChoice.Recent) + quarkEmotes.map(GestureChoice::Quark)
    } else {
        buildList {
            if (draft.annotation.hasEcho) add(GestureChoice.Keep)
            add(GestureChoice.Recent)
            addAll(quarkEmotes.map(GestureChoice::Quark))
            if (draft.annotation.hasEcho) add(GestureChoice.Remove) else add(GestureChoice.None)
        }
    }

    private fun cycleGesture() {
        val choices = gestureOptions()
        draft.gesture = choices[(choices.indexOf(draft.gesture).coerceAtLeast(0) + 1) % choices.size]
        gestureButton.message = gestureLabel(); refreshSave()
    }

    private fun refreshSave() {
        if (!::saveButton.isInitialized) return
        val hasGesture = when (draft.gesture) {
            GestureChoice.Keep, GestureChoice.Recent, is GestureChoice.Quark -> true
            GestureChoice.Remove, GestureChoice.None -> false
        }
        val editable = draft.publication == DraftPublication.EDITING && !AnnotationDrafts.isPreviousSession(draft)
        saveButton.active = editable && (draft.text.isNotBlank() || draft.icon.isNotEmpty() || hasGesture)
        deleteButton?.active = editable
        textBox.setEditable(editable)
        iconButton.active = editable
        colorButton.active = editable
        gestureButton.active = editable
    }

    private fun save() {
        draft.text = textBox.value.trim()
        val requestId = UUID.randomUUID()
        val sessionId = AnnotationDrafts.submit(requestId, draft) ?: return
        refreshSave()
        when (val choice = draft.gesture) {
            is GestureChoice.Quark -> runCatching {
                QuarkEmoteBridge.request(choice.id)
                DeathEchoRecorder.captureSelectedEmote(
                    { clip -> sendMutation(requestId, sessionId, EchoMutation.REPLACE, clip) },
                    { error -> AnnotationDrafts.failLocal(requestId, error) },
                )
            }.onFailure { AnnotationDrafts.failLocal(requestId, it) }
            GestureChoice.Recent -> {
                val clip = draft.recentClip
                if (clip == null) AnnotationDrafts.failLocal(requestId, IllegalStateException("the recent three-second pose recording is unavailable"))
                else sendMutation(requestId, sessionId, EchoMutation.REPLACE, clip)
            }
            GestureChoice.Keep -> sendMutation(requestId, sessionId, EchoMutation.KEEP, null)
            GestureChoice.Remove -> sendMutation(requestId, sessionId, EchoMutation.REMOVE, null)
            GestureChoice.None -> sendMutation(requestId, sessionId, EchoMutation.KEEP, null)
        }
    }

    private fun sendMutation(requestId: UUID, sessionId: UUID, echoMutation: EchoMutation, clip: ByteArray?) {
        val dimension = AnnotationDrafts.pendingDimension(requestId, sessionId) ?: return
        val annotation = draft.annotation
        val invalid = runCatching {
            AnnotationComponents.validate(draft.text, draft.icon, draft.color,
                if (annotation == null) clip != null else when (echoMutation) {
                    EchoMutation.KEEP -> annotation.hasEcho
                    EchoMutation.REPLACE -> true
                    EchoMutation.REMOVE -> false
                })
            clip?.let { com.bettercontent.betterplayertraces.logic.AnnotationEchoValidation.decode(it) }
        }.exceptionOrNull()
        if (invalid != null) { AnnotationDrafts.failLocal(requestId, invalid); return }
        runCatching {
            if (annotation == null) TracesNetwork.createAnnotation(
                AnnotationCreatePacket(requestId, draft.target.asLong(), draft.text, draft.icon, draft.color, echoMutation, clip, sessionId, dimension),
            ) else TracesNetwork.updateAnnotation(
                AnnotationUpdatePacket(requestId, annotation.id, annotation.revision, draft.text, draft.icon, draft.color, echoMutation, clip, sessionId, dimension),
            )
        }.onFailure { AnnotationDrafts.markTransportUnknown(requestId) }
    }

    private fun iconLabel() = Component.literal("Icon: ${draft.icon.ifEmpty { "None" }}")
    private fun colorLabel(): Component {
        val name = colors.firstOrNull { it.value == draft.color }?.key ?: "None"
        return Component.literal("Color: $name")
    }
    private fun gestureLabel() = Component.literal("Gesture: " + when (val choice = draft.gesture) {
        GestureChoice.None -> "None"; GestureChoice.Keep -> "Keep"; GestureChoice.Recent -> "Recent 3s"
        GestureChoice.Remove -> "Remove"; is GestureChoice.Quark -> choice.id
    })

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 94, 0xFFFFFF)
        refreshSave()
        val status = if (AnnotationDrafts.isPreviousSession(draft))
            "Previous session draft (read only); check world before dismissing"
        else when (draft.publication) {
            DraftPublication.EDITING -> draft.error
            DraftPublication.PENDING -> "Publishing note; success requires durable archive confirmation"
            DraftPublication.INDETERMINATE -> "Publication unknown; do not resend with a new request ID"
        }
        if (status.isNotBlank()) graphics.drawCenteredString(font, status.take(72), width / 2, height / 2 + 18,
            if (draft.publication == DraftPublication.EDITING) 0xFF6666 else 0xFFAA55)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean =
        if (openingKeyGuard.suppressCharacterInput()) true else super.charTyped(codePoint, modifiers)

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        openingKeyGuard.onKeyReleased(keyCode)
        return super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun isPauseScreen(): Boolean = false
}
