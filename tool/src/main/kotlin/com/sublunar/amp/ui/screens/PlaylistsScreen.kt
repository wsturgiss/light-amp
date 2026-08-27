package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.rememberListAnchor
import kotlinx.coroutines.isActive
import com.sublunar.amp.App
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.components.AppArtwork
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.LibraryList
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.SelectionArtwork
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.pxSp
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.ROW_SUB_LINE_PX
import com.sublunar.amp.ui.components.ROW_SUB_PX
import com.sublunar.amp.ui.components.ROW_TITLE_LINE_PX
import com.sublunar.amp.ui.components.ROW_TITLE_PX
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// Thicker than the Now Playing tab underline so it reads under a finger.
private const val DROP_LINE_HEIGHT_PX = 9

class PlaylistDetailScreen(
    sealed: SealedLightActivity,
    private val playlistId: String,
    private val playlistName: String,
) : SimpleLightScreen<Unit>(sealed) {

    // Held on the screen instance so local edits (remove / reorder) survive the
    // push/pop of the track-options sheet without a fresh server round-trip.
    private val entries = mutableStateOf<List<PlaylistEntry>?>(null)

    // Duplicate songs can appear in a playlist, so rows use a synthetic key, not track.id.
    private var nextEntryKey = 0
    private fun newEntryKey(): String = "e${nextEntryKey++}"

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        LaunchedEffect(playlistId) {
            if (entries.value == null) {
                entries.value = App.library.playlistTracks(playlistId).map { PlaylistEntry(newEntryKey(), it) }
            }
        }

        val selection = rememberSelection("playlist:$playlistId")

        // The stored name, not the one this screen was opened with: the corner
        // menu can rename the playlist while this page is under it, and the
        // constructor's copy would keep saying the old name until reopened.
        val liveName = App.library.playlists.collectAsState().value
            .firstOrNull { it.id == playlistId }?.name ?: playlistName

        LibrarySubPage(LibraryPage.PLAYLIST, liveName) {
            if (selection.active) {
                SelectionHeader(
                    selection = selection,
                    onDelete = {
                        removeSongs(selection.selected)
                        // Stay in edit mode with an empty selection: pruning a
                        // playlist is usually more than one pass, and the X is
                        // right there when it isn't.
                        selection.begin()
                    },
                    onConfirm = {
                        openSelectionActions(
                            selection.pick(entries.value.orEmpty()) { it.key }.map { it.track },
                            selection,
                        )
                    },
                )
            } else {
                AppHeader(
                    onBack = { goBack() },
                    title = liveName,
                    // The playlist's own menu, not the library's sort: a
                    // playlist plays in its own order, so the corner that
                    // offered a dead sort now offers its verbs — play, rename,
                    // delete. Same menu as a long-press on its row; see the
                    // album page, which made the same trade.
                    rightAction = HeaderAction(
                        AppIcons.MoreVert,
                        onLongClick = { go { SettingsScreen(it) } },
                    ) {
                        go { PlaylistActionsScreen(it, playlistId, liveName, fromDetail = true) }
                    },
                    fitTitle = true,
                )
            }
            // Edits apply locally right away; this indicates the server save hasn't landed yet.
            if (playlistId in App.library.pendingPlaylistWrites.collectAsState().value) SavingIndicator()
            when (val list = entries.value) {
                null -> Centered("Loading…")
                else -> if (list.isEmpty()) Centered("Empty playlist") else TrackList(list, selection)
            }
        }
    }

    @Composable
    private fun SavingIndicator() {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = px(LIST_EDGE_PX), vertical = px(26)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(px(41)),
                strokeWidth = px(5),
                color = LightThemeTokens.colors.content,
            )
            Spacer(Modifier.width(px(26)))
            AppText("Saving…", pxSp(36), dim = true)
        }
    }

    /**
     * Edit mode is one mode, not two: the grab bars appear and rows become
     * selectable together. Both are things you do to the playlist rather than
     * with it, and splitting them would mean two toggles competing for the same
     * corner of a header that has no spare room.
     */
    /** One playlist row: [key] is a synthetic per-row id (see [entries]); [track] is what it shows. */
    private data class PlaylistEntry(val key: String, val track: Track)

    @Composable
    private fun TrackList(list: List<PlaylistEntry>, selection: SelectionState) {
        val editing = selection.active
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffsetY by remember { mutableStateOf(0f) }
        // Portion of dragOffsetY caused by auto-scroll, not the finger; needed to avoid
        // feedback loops when computing edge proximity and the overlay's screen position.
        var autoScrolledPx by remember { mutableStateOf(0f) }
        // Grabbing a handle within a multi-row selection drags the whole selection.
        var draggingKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
        // Each dragged row's on-screen top at drag start, relative to the list container.
        var dragStartTops by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
        var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val rowCoords = remember { mutableMapOf<String, LayoutCoordinates>() }
        // Drag handle hit regions, checked by the container-level gesture below.
        val iconCoords = remember { mutableMapOf<String, LayoutCoordinates>() }
        val rowPx = with(LocalDensity.current) { px(160).toPx() }
        val headerCount = if (editing) 0 else 2
        val listState = rememberListAnchor("playlist:$playlistId", headerCount)
        // Where the drag would land right now; null beforeKey means "at the very bottom".
        val dropTarget: DropTarget? = draggingIndex?.let { from ->
            val target = dragRowTarget(list, from, dragOffsetY, rowPx)
            // Keyed on the row-granular target, not dragOffsetY, so it only recomputes
            // when the drag crosses into a new row.
            remember(list, from, draggingKeys, target) { DropTarget(dropAnchor(list, from, draggingKeys, target)) }
        }

        // Auto-scroll while a drag is pinned against an edge; nudge dragOffsetY to match
        // so the dragged row(s) stay glued under the finger.
        LaunchedEffect(draggingIndex) {
            val from = draggingIndex ?: return@LaunchedEffect
            val anchorKey = list.getOrNull(from)?.key ?: return@LaunchedEffect
            val startTop = dragStartTops[anchorKey] ?: return@LaunchedEffect
            var lastFrameMs = 0L
            while (isActive) {
                val frameMs = withFrameMillis { it }
                val dtMs = if (lastFrameMs == 0L) 0L else (frameMs - lastFrameMs).coerceAtMost(48L)
                lastFrameMs = frameMs
                if (dtMs == 0L) continue
                val viewportHeight = containerCoords?.size?.height?.toFloat() ?: continue
                val pointerY = startTop + rowPx / 2f + (dragOffsetY - autoScrolledPx)
                val overTop = rowPx - pointerY
                val overBottom = pointerY - (viewportHeight - rowPx)
                val pull = when {
                    overTop > 0f -> -(overTop / rowPx).coerceIn(0f, 1f)
                    overBottom > 0f -> (overBottom / rowPx).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (pull != 0f) {
                    val delta = pull * rowPx * 6f * dtMs / 1000f
                    val consumed = listState.scrollBy(delta)
                    dragOffsetY += consumed
                    autoScrolledPx += consumed
                }
            }
        }

        // Drag handling lives on this container rather than each row's icon, since
        // LazyColumn can recycle the row (and cancel its pointerInput) mid-drag.
        // Consuming Initial pass beats LazyColumn's scroll and the row's click handler.
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { containerCoords = it }
                .then(
                    if (!editing) {
                        Modifier
                    } else {
                        Modifier.pointerInput(list) {
                            awaitEachGesture {
                                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                val container = containerCoords?.takeIf { it.isAttached } ?: return@awaitEachGesture
                                val hitKey = iconCoords.entries.firstOrNull { (_, coords) ->
                                    coords.isAttached && container.localBoundingBoxOf(coords).contains(down.position)
                                }?.key ?: return@awaitEachGesture
                                val hitIndex = list.indexOfFirst { it.key == hitKey }
                                if (hitIndex < 0) return@awaitEachGesture
                                down.consume()
                                val keys = if (hitKey in selection.selected && selection.count > 1) {
                                    selection.selected
                                } else {
                                    setOf(hitKey)
                                }
                                dragStartTops = keys.mapNotNull { k ->
                                    rowCoords[k]?.takeIf { it.isAttached }
                                        ?.let { k to container.localPositionOf(it, Offset.Zero).y }
                                }.toMap()
                                draggingIndex = hitIndex
                                dragOffsetY = 0f
                                autoScrolledPx = 0f
                                draggingKeys = keys
                                try {
                                    val pointerId = down.id
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                        if (!change.pressed) {
                                            change.consume()
                                            break
                                        }
                                        dragOffsetY += change.positionChange().y
                                        change.consume()
                                    }
                                    val from = draggingIndex
                                    if (from != null) {
                                        val target = dragRowTarget(list, from, dragOffsetY, rowPx)
                                        reorderGroup(dropAnchor(list, from, draggingKeys, target), draggingKeys)
                                    }
                                } finally {
                                    draggingIndex = null
                                    dragOffsetY = 0f
                                    autoScrolledPx = 0f
                                    draggingKeys = emptySet()
                                }
                            }
                        }
                    },
                ),
        ) {
            LibraryList(
                anchor = "playlist:$playlistId",
                headerCount = headerCount,
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!editing) {
                    item {
                        PlayAllRow(AppIcons.Shuffle, "Shuffle") {
                            App.playback.playQueue(shuffled(list.map { it.track }), 0)
                            go { NowPlayingScreen(it) }
                        }
                    }
                    item { PlayAllRow(AppIcons.Dehaze, "Edit") { selection.begin() } }
                }
                itemsIndexed(list, key = { _, e -> e.key }) { index, entry ->
                    val track = entry.track
                    val isDragging = entry.key in draggingKeys
                    // Clear coords on dispose: LazyColumn recycles nodes, so a stale
                    // entry would silently report the next occupant's position.
                    DisposableEffect(entry.key) {
                        onDispose {
                            rowCoords.remove(entry.key)
                            iconCoords.remove(entry.key)
                        }
                    }
                    Column(Modifier.fillMaxWidth()) {
                        if (dropTarget?.beforeKey == entry.key) DropIndicatorLine()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(px(160))
                                .onGloballyPositioned { rowCoords[entry.key] = it }
                                // Real row goes invisible; DragOverlay draws the floating stand-in.
                                .alpha(if (isDragging) 0f else 1f)
                                .rowClickable(
                                    onClick = {
                                        if (editing) {
                                            selection.toggle(entry.key)
                                        } else {
                                            App.playback.playQueue(list.map { it.track }, index)
                                            go { NowPlayingScreen(it) }
                                        }
                                    },
                                    onLongClick = {
                                        if (editing) return@rowClickable
                                        go {
                                            TrackActionsScreen(
                                                it, track.id,
                                                onSelect = { selection.begin(entry.key) },
                                                onRemoveFromPlaylist = { removeSong(index) },
                                            )
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (editing) {
                                SelectionArtwork(track.coverArtId, entry.key in selection.selected)
                            } else {
                                AppArtwork(track.coverArtId, size = px(128))
                            }
                            Spacer(Modifier.width(px(ROW_GAP_PX)))
                            Column(Modifier.weight(1f)) {
                                AppText(track.title, pxSp(ROW_TITLE_PX), lineHeight = pxSp(ROW_TITLE_LINE_PX), maxLines = 1)
                                AppText(track.artist, pxSp(ROW_SUB_PX), lineHeight = pxSp(ROW_SUB_LINE_PX), dim = true, maxLines = 1)
                            }
                            // Drag handle for reordering within the playlist.
                            if (editing) AppIcon(
                                AppIcons.Dehaze,
                                size = px(51),
                                modifier = Modifier.onGloballyPositioned { iconCoords[entry.key] = it },
                            )
                        }
                    }
                }
                if (dropTarget != null && dropTarget.beforeKey == null) {
                    item { DropIndicatorLine() }
                }
            }
            if (draggingIndex != null) {
                DragOverlay(list, draggingKeys, dragStartTops, dragOffsetY - autoScrolledPx, selection)
            }
        }
    }

    /**
     * Floating copy of the row(s) being dragged, drawn outside the LazyColumn so it
     * survives the real row being recycled by auto-scroll. [fingerOffsetY] excludes
     * auto-scroll's own contribution, since the overlay's position shouldn't move twice.
     */
    @Composable
    private fun BoxScope.DragOverlay(
        list: List<PlaylistEntry>,
        draggingKeys: Set<String>,
        dragStartTops: Map<String, Float>,
        fingerOffsetY: Float,
        selection: SelectionState,
    ) {
        list.forEach { entry ->
            if (entry.key !in draggingKeys) return@forEach
            val top = dragStartTops[entry.key] ?: return@forEach
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(px(160))
                    .align(Alignment.TopStart)
                    .graphicsLayer { translationY = top + fingerOffsetY }
                    .padding(start = px(LIST_EDGE_PX), end = px(SCROLLBAR_LANE_PX)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionArtwork(entry.track.coverArtId, entry.key in selection.selected)
                Spacer(Modifier.width(px(ROW_GAP_PX)))
                Column(Modifier.weight(1f)) {
                    AppText(entry.track.title, pxSp(ROW_TITLE_PX), lineHeight = pxSp(ROW_TITLE_LINE_PX), maxLines = 1)
                    AppText(entry.track.artist, pxSp(ROW_SUB_PX), lineHeight = pxSp(ROW_SUB_LINE_PX), dim = true, maxLines = 1)
                }
                Spacer(Modifier.width(px(51)))
            }
        }
    }

    private data class DropTarget(val beforeKey: String?)

    /** Where row [from] would land right now, clamped to the list's bounds. */
    private fun dragRowTarget(list: List<PlaylistEntry>, from: Int, dragOffsetY: Float, rowPx: Float): Int =
        (from + (dragOffsetY / rowPx).roundToInt()).coerceIn(0, list.lastIndex)

    /**
     * The row the dragged block would land in front of right now, or null
     * meaning "at the very end". [target] is worked out as if only the grabbed
     * row ([from]) were moving — the same math a solo drag has always used —
     * so a multi-row selection tracks the finger exactly like a single row
     * would, instead of running out of room early because the rest of the
     * selection shrinks the pool of remaining rows to land among. The other
     * selected rows just ride along: from that anchor point we walk forward to
     * the nearest row that isn't itself part of the drag, since the block
     * can't be inserted in front of a row that's also about to move.
     *
     * Used for both the preview line and the actual drop ([reorderGroup]), so
     * the two can never disagree about where the drag will land.
     */
    private fun dropAnchor(list: List<PlaylistEntry>, from: Int, keys: Set<String>, target: Int): String? {
        // Index math avoids materializing a list-minus-`from` copy.
        val soloSize = list.size - 1
        val clampedTarget = target.coerceIn(0, soloSize)
        val anchorIndex = when {
            clampedTarget == soloSize -> list.size
            clampedTarget < from -> clampedTarget
            else -> clampedTarget + 1
        }
        return list.subList(anchorIndex, list.size).firstOrNull { it.key !in keys }?.key
    }

    @Composable
    private fun DropIndicatorLine() {
        Box(
            Modifier
                .fillMaxWidth()
                .height(px(DROP_LINE_HEIGHT_PX))
                .background(LightThemeTokens.colors.content),
        )
    }

    @Composable
    private fun Centered(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText(text, pxSp(LightType.DETAIL_PX), dim = true)
        }
    }

    private fun removeSong(index: Int) {
        val current = entries.value ?: return
        if (index !in current.indices) return
        entries.value = current.toMutableList().apply { removeAt(index) }
        App.scope.launch {
            App.library.removeFromPlaylistAt(playlistId, index)
            App.library.refreshPlaylists()
        }
    }

    /**
     * Remove several at once by rewriting the playlist with the survivors: the
     * per-index endpoint would shift every index behind each removal, and one
     * request can't half-apply.
     */
    private fun removeSongs(keys: Set<String>) {
        val current = entries.value ?: return
        if (keys.isEmpty()) return
        val remaining = current.filterNot { it.key in keys }
        if (remaining.size == current.size) return
        entries.value = remaining
        App.scope.launch {
            App.library.reorderPlaylist(playlistId, remaining.map { it.track.id })
            App.library.refreshPlaylists()
        }
    }

    /**
     * Move [keys] as a block to just in front of [beforeKey] (or the very end,
     * when null): pull them out in their current relative order, then reinsert
     * them there. For a lone dragged row this is the familiar single-row
     * reorder; for a multi-row selection the whole set rides along together,
     * so moving one selected row moves them all.
     */
    private fun reorderGroup(beforeKey: String?, keys: Set<String>) {
        val current = entries.value ?: return
        if (keys.isEmpty()) return
        val moving = current.filter { it.key in keys }
        if (moving.isEmpty() || moving.size == current.size) return
        val remaining = current.filterNot { it.key in keys }
        val insertAt = beforeKey?.let { key -> remaining.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }
            ?: remaining.size
        val newList = remaining.toMutableList().apply { addAll(insertAt, moving) }
        if (newList == current) return
        entries.value = newList
        App.scope.launch {
            App.library.reorderPlaylist(playlistId, newList.map { it.track.id })
            App.library.refreshPlaylists()
        }
    }
}
