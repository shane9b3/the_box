# Weather page (2026-11-19)

Owner request: a Weather tab at the top of the guide that opens a full-page forecast like the supplied mock-up
(`design/Weather@2x.png`), without the footer hints, with background pictures that match the weather and the time of day,
an hourly strip that follows the selected day, several cities with a default, and a working live radar page.

No proposer/reviewer round was held (the owner asked for it directly in a working session); this note is the record.

## Owner rulings

- **Forecast and city search**: Open-Meteo (no key). Its free API is for non-commercial use: if The Box is ever sold or
  carries ads, this needs their paid plan or another source.
- **Radar**: both. Environment Canada (MSC GeoMet WMS) for Canadian cities, RainViewer everywhere else; if Environment
  Canada does not answer, RainViewer is used.
- **Backgrounds**: free-licence photographs, bundled. The 13 pictures came from Wikimedia Commons (CC0, public domain or
  CC BY only); each still needs the owner's approval. Sources: `ui/src/main/assets/licenses/weather-backgrounds.txt`.

## What was built

- **Tab**: Guide · Search · Sports · **Weather** · gear (`TAB_WEATHER` 3, `TAB_SETTINGS` 4, `TAB_COUNT` 5).
  `ScreenMode.WEATHER` is handled as `SPORTS` is: covered hole, Compose focus, one Back to the guide. To fit five tabs at
  1024 px the header's tab padding went 18 → 14, the gap 8 → 4, the lead 24 → 16, and the date's maximum 220 → 150.
- **Forecast page** (`ui/weather/WeatherScreen.kt`): the mock-up's layout. Now (icon, temperature, condition, feels-like,
  high/low, one generated sentence), six tiles, twelve hourly columns, seven day cards. No footer hints.
- **Day cards**: focus selects. Today shows the next 12 hours; another day shows every second hour of that day
  (00:00 … 22:00), so a whole day fits the same twelve columns. The top half always shows current conditions.
- **Cities** (`CityPanel.kt`): the city chip opens a panel: show a city (for this visit), Set as default, Remove, Add a
  city (search by name), and Units (metric / imperial). Up to 12 cities. The first city added becomes the default.
  Stored in the new `weather` settings domain (`WeatherPrefs`), which is backed up.
- **No device location**: the page never reads it. With no city added it makes no request and asks for one.
- **Backgrounds**: `WeatherScene` = sky × time of day (13 scenes; a clear or partly cloudy sky within 40 min of sunrise
  or sunset shows the dusk picture). 1280 × 720 WebP under a dark scrim.
- **Radar page** (`RadarPage.kt`): a bundled base map (Natural Earth 1:50m land, lakes, borders, place names;
  `assets/weather/basemap.bin`, built by `tools/weather/build_basemap.py`), so no map tiles are fetched. Radar frames
  loop (0.6 s a frame, 1.8 s on the newest); step back / Play-Pause / step forward, a timeline, and four zoom steps.
  Environment Canada: one picture per frame, the last two hours at 12 min steps, the snow layer at or below 0.5 °C.
  RainViewer: the newest eight frames as tiles (it serves zoom 7 at most, so the closest zoom step is upscaled).
- **Requests** (`data/weather/WeatherRepositoryImpl.kt`): only while the page is open; the forecast is re-read every
  15 min (10 min cache), the radar frame list every 5 min. A request carries the city's coordinates or the typed name.
  URLs are never logged.
- **Credits**: "Weather data by Open-Meteo.com" on the page; the radar source and Natural Earth on the radar page;
  Settings › About › licences lists the data sources and the photographs.

## Not done

- The top half does not change with the selected day (only the hourly strip does, as asked).
- No weather alerts, no air quality, no precipitation-type legend on the radar.
- The top-bar tabs on the Weather page are for orientation only (as in Search); Back returns to the guide.
- Strings are English only, like the rest of the app.
