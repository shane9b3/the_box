# Xtream fixtures (WP-C, docs/design/m1-data-layer.md §5.2)

Hand-shaped after the fake panel's clean and `--quirks` responses (`tools/fakepanel/catalog.py`). Reference time
`T0 = 1791919800` = 2026-10-13T19:30:00Z.

| File | Covers |
|---|---|
| `login_ok.json` | All strings (`max_connections`, `exp_date`, `is_trial`), `allowed_output_formats` array, IANA `Europe/Berlin`, `time_now` = T0 in CEST |
| `login_ints.json` | Ints and bools, `exp_date: null` (unlimited), formats as a CSV string, `America/New_York` |
| `login_xui.json` | `xui: true`, `version`, `timezone: "UTC+3"` (non-IANA → offset path), `exp_date: "0"`, string `timestamp_now` |
| `login_auth0.json`, `login_expired.json`, `login_banned.json` | `auth: 0` → BAD_CREDENTIALS; `Expired`; `Banned` |
| `login_empty_array.json`, `login_empty.json`, `login_html.html` | `[]`, 0 bytes, HTML "expired" page |
| `live_categories.json` | String and numeric `category_id`, `parent_id` 0/"0"/"2"/null, empty name, an entry without id |
| `live_streams.json` | 20 streams: `tv_archive` `"1"`/`1`/`true`/`"0"`, `tv_archive_duration` `"7"`/`3`/`""`/`"5.0"`, `epg_channel_id` `null`/`""`/`"none"`/padded, `category_id: null` and unknown, XUI `category_ids`, `radio_streams`, `is_adult:"1"`, `direct_source`, `num` as string, `stream_id` as string/float/missing, empty name, Unicode |
| `live_streams_object.json` | The same as `{"0":{…},"1":{…}}` |
| `live_streams_bom.json` | UTF-8 BOM + whitespace before the first 3 streams |
| `vod_streams.json`, `vod_info.json`, `vod_info_empty_info.json`, `vod_info_string_backdrop.json` | `info: []`, `backdrop_path` array vs string, `duration` `"01:42:10"` only, decimal-comma rating |
| `series.json` | `releaseDate` vs `release_date` (and an empty `releaseDate`), `backdrop_path` `[]`/string/array |
| `series_info_map.json`, `series_info_array.json`, `series_info_flat.json`, `series_info_no_seasons.json` | `episodes` as an object, an array of arrays (index + 1 unless `season` is set), a flat array; empty `seasons` |
| `short_epg_base64.json`, `short_epg_plain.json`, `short_epg_mixed.json`, `short_epg_empty.json` | Base64 vs plain; plain titles that happen to be valid base64 (`"Test"`, `"QUJD"`) |
| `simple_data_table.json` | `has_archive` 0/1/"1", `start`/`end` in the panel zone (Berlin) **without** `*_timestamp` on two rows |

`../catchup/golden.tsv`: catch-up golden matrix (§5.4). Expected URLs come from an independent Python reference
implementation of §3.8.4–3.8.6 (zoneinfo), not from the Kotlin builder. The xc rows double as fakepanel
`timeshift_expect` values.
