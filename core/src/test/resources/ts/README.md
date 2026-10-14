# M2 TS and HTTP fixtures

Byte fixtures for the M2 transport tests (`docs/design/m2-playback-core.md` §5.2). They live in `:core` so the
pure-JVM tests own them. `:player`'s host tests see the same directory as a resource root (§6.3). WP-B owns them
after commit 0.

Generated once on 2026-10-13 with **ffmpeg 4.4.4** (MacPorts, `/opt/local/bin/ffmpeg`, libx264 and the native
`aac`/`mp2` encoders). Re-running the commands gives equivalent files but not byte-identical ones: x264 and the muxer
are not run in bitexact mode. Run them from `core/src/test/resources/ts/`.

| File | Size | Content | Used by |
|---|---|---|---|
| `ts/h264_aac_2s.ts` | 95,128 B | 2 s, 320×180, 25 fps, H.264 Constrained Baseline, IDR every 25 frames, AAC-LC 44.1 kHz stereo 64 kb/s. PIDs: PAT, PMT `0x1000`, video `0x100` (carries the PCR), audio `0x101`, SDT. First PCR 0.723 s, first video PTS 1.423 s | sniffer, aligner, PCR tracker, LiveTs continuity, Robolectric ExoPlayer |
| `ts/h264_mp2_2s.ts` | 95,316 B | As above with MPEG-1 Layer II audio (`audio/mpeg-L2`) | MIME mapping, selector gating |
| `ts/pcr_jump.bin` | 190,256 B | `h264_aac_2s.ts` followed by the same streams with PCR/PTS/DTS shifted by exactly +10 s. The second half restarts every continuity counter at 0, as a reconnecting server does. The jump is at byte 95,692 (PCR 2.643 s → 10.723 s) | PCR tracker jump |
| `ts/garbage_prefix.ts` | 95,225 B | 97 deterministic bytes (none is `0x47`), then `h264_aac_2s.ts` | sniffer offset, aligner resync |
| `http/splash.html` | text | A provider's "subscription expired" page (`<!DOCTYPE html>`) | sniffer `HTML` |
| `http/hls_live.m3u8` | text | A live HLS media playlist (no `#EXT-X-ENDLIST`) | sniffer `HLS_PLAYLIST`, `FormatIsHls` |
| `http/tiny.bin` | 200 B | 200 deterministic bytes, none is `0x47` | sniffer `TINY` |
| `http/mp4_head.bin` | 2,048 B | The first 2,048 bytes (`SNIFF_MAX_BYTES`) of a fast-start MP4: `ftyp` (`isom`), then the start of `moov` | sniffer `UNRECOGNIZED` |

## Commands

```sh
# h264_aac_2s.ts: the design's command plus `-ac 2`. `sine` is mono and the design asks for AAC-LC stereo.
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=25 -f lavfi -i sine=frequency=1000 -t 2 \
  -c:v libx264 -profile:v baseline -g 25 -pix_fmt yuv420p -c:a aac -b:a 64k -ac 2 \
  -f mpegts -mpegts_flags +resend_headers h264_aac_2s.ts

# h264_mp2_2s.ts
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=25 -f lavfi -i sine=frequency=1000 -t 2 \
  -c:v libx264 -profile:v baseline -g 25 -pix_fmt yuv420p -c:a mp2 -b:a 64k -ac 2 \
  -f mpegts -mpegts_flags +resend_headers h264_mp2_2s.ts

# pcr_jump.bin. The design suggested a small Kotlin helper. A stream-copy remux with -output_ts_offset shifts PCR, PTS
# and DTS consistently and needs no new code.
ffmpeg -i h264_aac_2s.ts -map 0 -c copy -output_ts_offset 10 -f mpegts -mpegts_flags +resend_headers /tmp/shifted.ts
cat h264_aac_2s.ts /tmp/shifted.ts > pcr_jump.bin

# garbage_prefix.ts
python3 -I -c 'import sys,random; r=random.Random(97); sys.stdout.buffer.write(bytes(b if b != 0x47 else 0x46 for b in (r.randrange(256) for _ in range(97))))' > /tmp/prefix.bin
cat /tmp/prefix.bin h264_aac_2s.ts > garbage_prefix.ts

# ../http/tiny.bin
python3 -I -c 'import sys,random; r=random.Random(200); sys.stdout.buffer.write(bytes(b if b != 0x47 else 0x46 for b in (r.randrange(256) for _ in range(200))))' > ../http/tiny.bin

# ../http/mp4_head.bin
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=25 -t 1 -c:v libx264 -profile:v baseline -pix_fmt yuv420p \
  -movflags +faststart -f mp4 /tmp/head.mp4
head -c 2048 /tmp/head.mp4 > ../http/mp4_head.bin
```

`http/splash.html` and `http/hls_live.m3u8` are hand-written.

Verified after generation with `ffprobe` (codecs, profile, channels) and a 188-byte packet walk: PIDs, the PCR PID, and the
first and last PCR/PTS of each half of `pcr_jump.bin`.
