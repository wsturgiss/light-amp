# Changelog

## Unreleased

### Added

- Cast to other Plex players. When the active source is Plex, the Output page
  lists the Plex players on your network — the Apple TV app among them — next
  to the DLNA speakers, over Plex's own Companion protocol. Players are found
  the way Plex's own controllers find them: the server's list, your account's
  devices, and a GDM broadcast to the network, so a player that slept and woke
  is still there. The player takes the queue and plays it itself (gapless,
  skippable from its own remote, scrobbling its own plays); Amp steers and
  mirrors — a skip or a repeat change on the TV shows up on the phone — and
  takes playback back at the position the player reached. Closing Amp
  deliberately leaves the player playing.

  Quality is settled between the player and your server: Amp's streaming
  setting doesn't apply while casting this way, and lossless depends on the
  player's own quality setting. The volume fader disables itself, at unity,
  when the player's volume isn't its to control — an Apple TV passing digital
  audio to a receiver is the common case.

### Changed

- Background audio is official. Playback now runs in the SDK's own detached
  audio service (`capabilities = ["detached-audio"]`, new in SDK 0.1.1)
  instead of the keep-alive spike this app carried since July — the largest
  off-SDK workaround, deleted in full. Two trades ship with it: a paused
  queue left alone for 15 minutes now winds down (the spike held on for
  ever), and tapping the media surface opens Amp.

### Fixed

- Switching source left the app holding one server's identity and another's
  music for a few seconds. Three separate watchers of the active source each
  changed part of the world — one the client, one the database, one the
  downloads — and nothing ordered them, so the source flipped first and the
  rest caught up afterwards. Anything running in that gap asked the new server
  for the old one's ids: a download top-up queued sixteen thousand Navidrome
  tracks against Plex, which rejected each one and put it back at the front of
  the queue to be retried every minute forever, and cover art from one server
  was fetched from the other, which tried to resolve the id as a hostname.
  The change is now one ordered operation — client, then database, then the
  source that everything else keys on — and the lists that follow the database
  empty the moment it changes rather than answering for a source already left.
  The playing queue goes with them: it was cleared a moment later than the
  switch itself, and in that moment the tracks in it were rebuilt against the
  server that had just arrived, which refused ids it had never heard of.

- Likes and ratings were sent to the server on cellular even in Wi-Fi Only.
  They had a queue already, for when the server can't be reached, but nothing
  asked whether the connection was allowed — so the one thing still going out
  regardless of the setting was the thing the user had just tapped. They now
  join that queue and reach the server when Wi-Fi does, the same way a play
  does — and the queue now empties as soon as the connection changes into one
  that allows it, rather than waiting for the next thing that happened to talk
  to the server. That could be half an hour after Wi-Fi came back, with
  nothing on screen to say anything was waiting.

- The Output page searched the local network for players even with Wi-Fi off,
  where there is no local network to search and nothing that could answer. It
  cost no data, only the wait for its own timeout on a page that could not
  find anything. Note that this asks whether there is a network to speak to,
  which is a different question from whether its bytes are free: a phone
  hotspot is Wi-Fi and metered, and still has neighbours worth asking.

- Lyrics were never stored with a downloaded Plex track, and every download
  spent a request discovering it. Amp asked for the lyric file with a header
  saying it wanted JSON — right for every other Plex endpoint, wrong for the
  one that serves the file itself — and Plex answered 404 every time.

- Downloaded MP3s from Plex showed the wrong length, played on in silence past
  their end, and seeked to the wrong place. Plex transcodes to MP3 as a live
  stream, and a stream can't carry the index — a frame at the front stating
  how many frames follow and where each percent begins — that an encoder
  writing to a file adds last, by seeking back. Without it the player guessed
  the length from the file size and the *first* frame's bitrate, which on a
  variable-rate encode was out by up to sevenfold: a 3:07 song read as 22:57,
  the clock running on through a phantom remainder with nothing to play, and
  every seek landing early. Amp now finishes the encoder's job: after a
  download it walks the frames, counts them, and writes the index LAME would
  have. Not one audio byte moves, and the result is what any decoder expects
  an MP3 to be. Downloads already on the phone are repaired at the next
  launch, once, in place — nothing is re-fetched. Navidrome and Jellyfin
  were never affected: they encode MP3 at a constant rate, where the guess
  happens to be right.

- Downloading an album fetched its cover even with artwork turned off — a
  quarter of a megabyte per record, for a picture the app had been told never
  to draw. Browsing already knew not to; downloading was the other way in.

- Wi-Fi Only let three things through. Lyrics were fetched on any connection —
  from the server and, failing that, from lrclib.net — and the timeline and
  now-playing reports that accompany a track carried on over cellular even
  while playing a song already on the phone. All three now respect the mode.
  Low Data is unchanged: it has always been about downloads and artwork, and
  still allows all of this.

  A play is treated differently from the rest, because it is durable where the
  others are momentary: rather than being dropped it joins the queue that
  already holds likes and ratings made out of reach, stamped with when it
  happened, and reaches the server when Wi-Fi does. A timeline heartbeat says
  where playback is *now* and is simply skipped — replaying a stale one later
  would be worse than sending nothing.

- The Downloads page sat still while songs were downloading. It read the list
  and the storage figure once, when the page opened, on the assumption that
  nothing would change while you looked at it — which is untrue in the one
  case you are most likely to have it open. Both now follow the downloads as
  they land.

- Opening the app fetched every playlist's contents twice. The membership each
  playlist needs for its download badge is fetched once and remembered, but the
  check for "already have it" could only see finished work — and two callers
  start it about three seconds apart at launch, so both looked, both found
  nothing, and both asked. On an 8,000-track playlist that was over a megabyte
  of the same answer twice, one copy of it abandoned half-way when its screen
  went away. A fetch already running is now shared, and it survives the screen
  that started it.

- Stopping the queue while casting to a Plex player left the player subscribed.
  Every other way out of a cast closes the subscription; this one sent the stop
  and walked away, so the player went on pushing its timeline to the phone —
  at a socket nobody was reading, and then at a dead port once the app closed.
  Nothing could tidy up afterwards either, since the player's own "stopped"
  push is handled by a path that returns as soon as the cast is forgotten.

- A Plex library was re-fetched in full on every sync. Only new or changed
  albums are meant to have their songs fetched, and "changed" was decided by
  comparing the server's song count against the cached one — but Plex leaves
  that count off its album listing, so every album reported zero against a
  cache holding the real number, every album looked changed, and all 708 of
  them were walked again. Once at every launch, and again every half hour for
  as long as the app was running, which with background audio is the whole
  time music is playing. A count the server didn't send is no longer read as
  a count of zero; where there is none, Amp compares the album's own
  modification time instead, which Plex does send and does move when a record
  gains a track. A steady library now costs two requests to sync rather than
  seven hundred and ten. The first sync after updating still walks the
  library once, to learn those times and pick up anything missed.

- Streamed tracks behave like downloaded ones again around the edges.
  A reopened app showed the restored track with a full bar and a total of
  0:00; Previous could only ever restart the song — from the wrong place —
  and never reach the previous track; the end of a streamed queue didn't
  park back at the start; and repeat modes could stop playback or wrap to
  the first track paused. One family of causes: the seeked/restored stream
  (a shorter file starting mid-track) was advertised with a guessed length
  that invited range requests the server answers with 416, the player's own
  duration never arrives for a live transcode, a queue edit could land
  after the seek that should follow it, and a seek during rebuffering read
  "paused" and never resumed. All are fixed and were verified against a
  real Navidrome on the emulator; none of this touched downloads, which is
  why it went unseen so long.

### Changed

- Light's SDK now comes in as the `light-sdk` submodule, pinned to upstream
  and pulled pristine; every change Amp makes to it travels as a patch file in
  `light-sdk-patch/` that the build applies by itself. Same code, honest
  shape — and an SDK update becomes a checkout instead of archaeology.
- SDK updated 0.0.12 → 0.1.1. Nothing here uses the new pieces yet, but they
  are the ones the next releases will build on: official background audio
  (`detached-audio`), an NFC reader API, `openDialer`. One behavioural edge
  moved: audio-focus handling now rides ExoPlayer's built-in path (upstream's
  change), so ducking and transient-loss resume come from Media3 rather than
  the SDK's old helper — worth an ear during phone-call and navigation-prompt
  interruptions.

## 0.5.0

### Fixed

- Data modes now hold everywhere. Downloads and cover fetches never run on
  metered data (cellular, or a phone-hotspot Wi-Fi).
- The network check reads the connection the phone is actually using, through
  the SDK's new ConnectivityManager hooks (light-sdk #163). A Wi-Fi that had
  lost internet used to still count as Wi-Fi while every byte rode cellular.
- The whole UI now sizes itself from the panel's real width, the way the
  phone's own tools do. Changing Android's "smallest width" (density) setting
  used to shrink every control and font while other apps stayed put; now Amp
  renders the same pixels at any setting, and scales properly on any future
  panel.

### Changed

- Low Data is now the default for new installs. It was Wi-Fi Only — which,
  now that Wi-Fi Only really means no network, would have left a first
  sign-in over cellular with nothing synced and no visible reason.


## 0.4.1

### Fixed

- Playing to the end of a queue closed the app, and reopening put you back at
  the same spot to do it again. Present since 0.3.0.

## 0.4.0

The first launch trims the artwork cache to 200 MB in the background; covers
come back as you browse.

### Added

- Start Song Radio, in every song's menu, on Navidrome, Plex and Jellyfin.
  Started from the playing song, it keeps the song and replaces what follows.
- Save Queue as Playlist, in the + menu.
- Hold the top-right button on any page, or on the player, to open Settings.
- Tap Random again in a sort menu for a new shuffle. The sort page says what a
  second tap does.
- Delete one server's downloads from the Offline page.
- An artist's popular songs are kept for a week and shown offline.

### Changed

- Settings is five pages: Appearance, Sources, Data, Offline and About.
  Streaming quality is under Data; auto-download and download quality are under
  Offline. A server's page keeps Connection, Player Name, Shown on Sources, Sync
  Now and Log Out.
- "Offline Mode" is now "Auto-Download".
- Album pages put track numbers in a gutter that fits three digits, and show the
  artist line only where the tracks' artists differ. The corner button opens the
  album's own menu, not a sort menu. Playlists likewise.
- The player's header is the same bar as everywhere else.
- Covers are fetched at 1080px.
- The "Plays" sort is "Frequently Played". Rating is offered only where the
  server can rate.
- Hide Artist Photos is on by default.

### Fixed

- Local Music said it had no access on phones where the permission was granted,
  and Sync Now led back to Allow Music Access.
- Switching from a cast back to This Device started the song over. It now
  picks up where the speaker was.
- While casting, dragging to the end of a song waited, then restarted it. It
  goes to the next song.
- Seeking while casting replayed the song from the top before jumping. It seeks
  in place.
- The clock flashed the previous song's time on Next while casting.
- A source switched during a sync could write one server's albums into
  another's library, where they showed in every library. Fixed, and the stray
  rows are removed on the next sync.
- Everything and Favorites never auto-downloaded playlist songs.
- Delete All Downloads emptied only the active source.
- The artwork cache grew without limit — 3 GB on one phone — keeping a copy of
  each cover per song. One per album now, 200 MB at most; the covers of
  downloaded albums are always kept.

### Removed

- The Monochrome Artwork setting. Color follows the artwork switch.

## 0.3.1

### Fixed

- Long tracks took minutes to start. A 29-minute track needed 68 seconds; the
  same 124-minute one now starts in under a second.
- Library tabs lost their place when you switched away and back.

### Added

- Album track rows show the length under the performer.

### Changed

- Full-screen artwork opens with the controls out of the way when you go to it;
  opening the player still brings them back.
- No artist pictures on the phone's own library, which has none to show.
- The Source row is hidden when there is one source with one library.

## 0.3.0

**Needs one online sync after updating.** Downloads are safe; the Downloads page
looks empty until the sync finishes.

### Fixed

- Artists listed track performers, so every "feat." guest became its own artist.
  Now album artists.
- Switching library while offline emptied the library.
- Playlists, Artists and Albums did nothing when pressed from an album page.
- The queue ended parked on the last song. It now returns to the first, stopped.
- Each source was measured against the size limit separately.
- The size limit now counts what is already downloaded.

### Added

- Search on the page you're in, results narrowing as you type.
- Genre and Composer filters on Albums and Songs.
- Offline browsing — every list shows what you can play.
- The Downloads page lists every library and source. Read-only.
- Liked is a single switch.
- The queue header says "3 of 12".
- Album pages show who plays on each song, not its length.
- Pressing the current tab returns to the top of it.

### Changed

- The expanded tab bar is the default again. The setting is now "Simplified
  Library View"; existing installs keep their layout.
- One storage limit for the whole app, in Settings.
- The bottom bar's fifth button is the player; search moved to the header.
- Every library stays cached, so switching is instant and works offline.

### Removed

- The Compilations page.
- The Genres and Composers pages, now filters.
- The More page.
- Each source's separate Downloads page.
- The per-source download library setting, and the Include Lyrics toggle.
