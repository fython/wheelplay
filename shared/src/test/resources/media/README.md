These two hex files are manually constructed CSM test frames based on the field mapping. They are not device captures.

- `now-playing-full.hex`: title Sample, artist Artist, album Album; duration 240000 ms, position 90000 ms, speed 1.5x, setting elapsed time allowed, artwork transfer ID 128.
- `now-playing-pause.hex`: changes only the playback status to paused, verifying incremental merging and stopped monotonic progress.

`Iap2NowPlayingCodecTest` replays these fixtures. Synthetic samples cannot establish the units of an outbound seek command.
