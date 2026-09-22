# Changelog

Notable user-facing changes to MeshCore Two, release by release. Dates are UTC+3 (Moscow).

## 1.1.18 — 2026-09-22

- **Chat:** conversations no longer cap out at the newest 100 messages — scrolling up now
  loads older history. Opening a conversation also jumps straight to the first unread
  message (with a "New Messages" divider) instead of always landing at the bottom.
- **Repeater/Room status:** the battery/RSSI trend arrow no longer points down for a reading
  that hasn't actually changed since the last visit.
- **Repeater/Room settings — Identity & Location:** fixed the Longitude field getting stuck
  permanently empty on some connections. Added a "Pick on Map" button to set a node's
  location from a map instead of typing coordinates by hand.
- **Contacts:** the list now opens on the Favorites tab. Fixed node names being truncated
  more aggressively than necessary, leaving a dead gap before the timestamp.
- **Maps:** repeater/neighbor/location pins with labels now draw correctly (a missing font
  reference on some map styles was silently failing the whole marker layer).
- **CLI:** the on-screen keyboard no longer covers the command input on the node CLI screen;
  fixed a crash triggered by the command autocomplete list.

## Earlier releases

Not tracked here yet — see the GitHub release list.
