package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberDragReorderState
import com.sublunar.amp.ui.components.dragReorderContainer
import com.sublunar.amp.ui.components.dragRowTarget
import com.sublunar.amp.ui.components.dropInsertIndex
import com.sublunar.amp.ui.components.AutoScroll
import com.sublunar.amp.ui.components.DropIndicatorLine
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SUCCESS_FLASH_MS = 1000L

class PlaylistDetailScreen(
    sealed: SealedLightActivity,
    private val playlistId: String,
    private val playlistName: String,
) : SimpleLightScreen<Unit>(sealed) {

    // Held on the screen instance so local edits (remove / reorder) survive the
    // push/pop of the track-options sheet without a fresh server round-trip.
    private val entries = mutableStateOf<List<PlaylistEntry>?>(null)

    // Rows with a write in flight -- edits are pessimistic (see removeSong), so this is
    // what tells a row's own UI it's waiting on the server, not just the top-of-screen
    // SavingIndicator.
    private val pendingKeys = mutableStateOf<Set<String>>(emptySet())

    // Rows whose write just landed, briefly -- a slow connection can leave a row
    // spinning long enough that its success is worth confirming, not just inferring
    // from the spinner going away.
    private val successKeys = mutableStateOf<Set<String>>(emptySet())

    // Set once a drag-reorder lands, to the topmost row that moved, so TrackList can
    // scroll it into view -- the drop happened somewhere the finger was, not necessarily
    // where the block actually landed once the list re-settles.
    private val scrollToKey = mutableStateOf<String?>(null)

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
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
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
                    when (val list = entries.value) {
                        null -> Centered("Loading…")
                        else -> if (list.isEmpty()) Centered("Empty playlist") else TrackList(list, selection)
                    }
                }
                // Dead center and above everything else, since a row-level cue (the
                // spinner on the affected row) is easy to miss on a slow connection.
                StatusOverlay(
                    pending = playlistId in App.library.pendingPlaylistWrites.collectAsState().value,
                    done = successKeys.value.isNotEmpty(),
                )
            }
        }
    }

    @Composable
    private fun BoxScope.StatusOverlay(pending: Boolean, done: Boolean) {
        // Pending wins the moment both are briefly true, e.g. a second edit landing
        // while the last one's success flash is still winding down.
        if (!pending && !done) return
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(px(24)))
                .background(LightThemeTokens.colors.content)
                .padding(horizontal = px(32), vertical = px(20)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(px(34)),
                    strokeWidth = px(4),
                    color = LightThemeTokens.colors.background,
                )
                Spacer(Modifier.width(px(16)))
                AppText("Saving…", pxSp(36), color = LightThemeTokens.colors.background)
            } else {
                AppIcon(AppIcons.Selected, size = px(41), tint = LightThemeTokens.colors.background)
                Spacer(Modifier.width(px(16)))
                AppText("Done", pxSp(36), color = LightThemeTokens.colors.background)
            }
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
        val drag = rememberDragReorderState<String>()
        val rowPx = with(LocalDensity.current) { px(160).toPx() }
        val headerCount = if (editing) 0 else 2
        val listState = rememberListAnchor("playlist:$playlistId", headerCount)
        val orderedKeys = remember(list) { list.map { it.key } }
        // Where the drag would land right now; null beforeKey means "at the very bottom".
        val dropTarget: DropTarget? = drag.draggingIndex?.let { from ->
            val target = dragRowTarget(list.size, from, drag.dragOffsetY, rowPx)
            val movingIndices = drag.draggingKeys.mapNotNull { orderedKeys.indexOf(it).takeIf { i -> i >= 0 } }.toSet()
            // Keyed on the row-granular target, not dragOffsetY, so it only recomputes
            // when the drag crosses into a new row.
            remember(list, from, drag.draggingKeys, target) {
                val insertAt = dropInsertIndex(list.size, from, movingIndices, target)
                val remaining = list.filterIndexed { i, _ -> i !in movingIndices }
                DropTarget(remaining.getOrNull(insertAt)?.key)
            }
        }

        drag.AutoScroll(listState, rowPx)

        // Scroll to where a just-confirmed reorder actually landed once the list has
        // settled into its new order.
        LaunchedEffect(list, scrollToKey.value) {
            val key = scrollToKey.value ?: return@LaunchedEffect
            val target = list.indexOfFirst { it.key == key }
            if (target >= 0) listState.animateScrollToItem(target)
            scrollToKey.value = null
        }

        Box(
            Modifier
                .fillMaxSize()
                .dragReorderContainer(
                    state = drag,
                    enabled = editing,
                    restartKey = list,
                    orderedKeys = orderedKeys,
                    rowPx = rowPx,
                    groupOf = { hitKey ->
                        if (hitKey in selection.selected && selection.count > 1) selection.selected else setOf(hitKey)
                    },
                    onDrop = { movingIndices, insertAt -> reorderGroup(movingIndices, insertAt) },
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
                    val isDragging = entry.key in drag.draggingKeys
                    // Clear coords on dispose: LazyColumn recycles nodes, so a stale
                    // entry would silently report the next occupant's position.
                    DisposableEffect(entry.key) {
                        onDispose { drag.clear(entry.key) }
                    }
                    Column(Modifier.fillMaxWidth()) {
                        if (dropTarget?.beforeKey == entry.key) DropIndicatorLine()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(px(160))
                                .onGloballyPositioned { drag.rowCoords[entry.key] = it }
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
                            if (entry.key in pendingKeys.value) {
                                Box(Modifier.size(px(51)), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(px(34)),
                                        strokeWidth = px(4),
                                        color = LightThemeTokens.colors.content,
                                    )
                                }
                            } else if (editing) {
                                // Drag handle for reordering within the playlist.
                                AppIcon(
                                    AppIcons.Dehaze,
                                    size = px(51),
                                    modifier = Modifier.onGloballyPositioned { drag.iconCoords[entry.key] = it },
                                )
                            }
                        }
                    }
                }
                if (dropTarget != null && dropTarget.beforeKey == null) {
                    item { DropIndicatorLine() }
                }
            }
            if (drag.draggingIndex != null) {
                DragOverlay(list, drag.draggingKeys, drag.dragStartTops, drag.fingerOffsetY, selection)
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

    @Composable
    private fun Centered(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText(text, pxSp(LightType.DETAIL_PX), dim = true)
        }
    }

    /** Shows [StatusOverlay]'s "Done" state for [SUCCESS_FLASH_MS], then clears it. */
    private suspend fun flashSuccess(keys: Set<String>) {
        successKeys.value = successKeys.value + keys
        delay(SUCCESS_FLASH_MS)
        successKeys.value = successKeys.value - keys
    }

    /**
     * Pessimistic on purpose: the on-screen order doesn't change until the server
     * confirms it, since [StatusOverlay] is the only sign an edit is in flight, and
     * there's currently no error surfaced on failure. `refreshPlaylists` runs after the
     * fact, unwaited -- it updates each playlist's track-count badge elsewhere, not
     * this write's own success, so it shouldn't hold up the flash confirming it.
     */
    private fun removeSong(index: Int) {
        val entry = entries.value?.getOrNull(index) ?: return
        pendingKeys.value = pendingKeys.value + entry.key
        App.scope.launch {
            try {
                if (App.library.removeFromPlaylistAt(playlistId, index)) {
                    entries.value = entries.value?.filterNot { it.key == entry.key }
                    App.scope.launch { App.library.refreshPlaylists() }
                    pendingKeys.value = pendingKeys.value - entry.key
                    flashSuccess(setOf(entry.key))
                }
            } finally {
                pendingKeys.value = pendingKeys.value - entry.key
            }
        }
    }

    /**
     * Remove several at once by rewriting the playlist with the survivors: the
     * per-index endpoint would shift every index behind each removal, and one
     * request can't half-apply. Pessimistic, like [removeSong].
     */
    private fun removeSongs(keys: Set<String>) {
        val current = entries.value ?: return
        if (keys.isEmpty()) return
        val remaining = current.filterNot { it.key in keys }
        if (remaining.size == current.size) return
        pendingKeys.value = pendingKeys.value + keys
        App.scope.launch {
            try {
                if (App.library.reorderPlaylist(playlistId, remaining.map { it.track.id })) {
                    entries.value = remaining
                    App.scope.launch { App.library.refreshPlaylists() }
                    pendingKeys.value = pendingKeys.value - keys
                    flashSuccess(keys)
                }
            } finally {
                pendingKeys.value = pendingKeys.value - keys
            }
        }
    }

    /**
     * Move the rows at [indices] as a block to position [insertAt] among the rest: pull
     * them out in their current relative order, then reinsert them there. For a lone
     * dragged row this is the familiar single-row reorder; for a multi-row selection the
     * whole set rides along together, so moving one selected row moves them all.
     * Pessimistic, like [removeSong]: a dropped row holds its old spot until the server
     * confirms the move, then jumps to its new one.
     */
    private fun reorderGroup(indices: Set<Int>, insertAt: Int) {
        val current = entries.value ?: return
        if (indices.isEmpty()) return
        val moving = current.filterIndexed { i, _ -> i in indices }
        if (moving.isEmpty() || moving.size == current.size) return
        val remaining = current.filterIndexed { i, _ -> i !in indices }
        val newList = remaining.toMutableList().apply { addAll(insertAt.coerceIn(0, remaining.size), moving) }
        if (newList == current) return
        val keys = moving.map { it.key }.toSet()
        // The block moves as a unit, so its relative order (and hence which row is
        // topmost) survives the move -- moving.first() is still the one to scroll to.
        val topmostKey = moving.first().key
        pendingKeys.value = pendingKeys.value + keys
        App.scope.launch {
            try {
                if (App.library.reorderPlaylist(playlistId, newList.map { it.track.id })) {
                    entries.value = newList
                    scrollToKey.value = topmostKey
                    App.scope.launch { App.library.refreshPlaylists() }
                    pendingKeys.value = pendingKeys.value - keys
                    flashSuccess(keys)
                }
            } finally {
                pendingKeys.value = pendingKeys.value - keys
            }
        }
    }
}
