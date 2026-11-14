# CHL team colours 2026-27 (WP-4): the sourced table and the owner's sign-off list

Implemented in `core/.../sports/ChlTeamColours.kt` (tables) and `StackedColourTable.kt` (Stacked pairs). Contact sheets
(Stacked pair with power-play bar, and Pill with power-play tab, per team) come from `ChlColourSheetTest`.

## Owner sign-off (design §7.4)

**The 8 low-confidence teams (flagged LOW in the code comments):** OHL BRAM, BFD, OSH, OS, SBY; QMJHL CHI, HAL, RIM.
Please check these by eye on the sheets. All 61 pairs need the usual sign-off.

**Primary-order calls:** TCC often lists the gold/red/silver first; the base is the jersey/logo base, with the other
colour in the accent slot (ER, KGN, SAR, BDN, CGY, POR, VAN, SHA, VDO, EVT, SAS, QMJHL VIC, DRU, ROU, GAT, CHA, SBY, BLB).
Red-first for KIT, REG, SPO, QUE, MON and BAC is by Wikipedia and the logo, not TCC. Any of them flips with a one-line
change in the table.

**Departures from the research table (2 hex values):** OHL NB `#807A11` to `#7F7911` and WHL KEL `#008394` to `#008293`,
1 % darker so white-on-primary (C1) passes 4.5 (they were 4.46 and 4.49). Imperceptible; no exception needed.

**C1 exceptions:** only WHL MH (orange `#E56B1F`, white on it 3.25 : 1; the brand colour, like the NHL's PHI, ruling 12).

**Look-alikes:** within a league the NHL rule (home swaps to its other colour, then away) separates every ordered pair
(OHL 42 of 380 pairs collide before the swap, WHL 58 of 506, QMJHL 50 of 306; none unresolved).

## Research (2026-11-13), sourced table


Researched 2026-11-13. Primary = base, secondary = accent, same convention as `NhlTeamColours` (ruling 12: the team's own colours, no contrast substitutions; a gold/light primary takes the accent slot, so black/navy base + gold accent). Near-black primaries are kept as-is (BOS, PIT, LAK precedent).

## Sources and method

- No team brand guides were retrievable for any of the 61 teams (I did not search every club site, so confidence is capped at high only where TCC, Wikipedia and the logo agree). So every row is a second-tier source.
- **TCC** = `teamcolorcodes.com/<slug>/` (scraped 2026-11-13; 57 pages exist, none for Brampton, Brantford, Penticton, Newfoundland; Hamilton Bulldogs and Mississauga Steelheads pages used as proxies for Brantford/Brampton). TCC lists colours in its own order, which is often wrong about which is primary (it lists gold/grey first for many dark-jersey teams). `sportsteamcolorcodes.com` was not consulted.
- **Wikipedia** infobox colour boxes (`action=raw`), the second cross-check for all 61.
- **Logo sample**: the HockeyTech logo PNG (`assets.leaguestat.com/{client}/logos/{id}.png`, or the `.jpg` listed in `teamsbyseason` for BRAM, NIAG, CAP etc.) was downloaded and its dominant colours measured; used as the arbiter when TCC and Wikipedia disagree on primary order or hue. Rows marked `SAMPLED` take the hex from the logo.
- Primary rule: TCC first colour unless both Wikipedia and the logo contradict it (then the majority); gold/light primaries move to the accent slot. Hex values: TCC where it agrees with the logo, else the value that matches the logo.
- ΔE76 is CIELAB (D65) on the primary hex, all pairs within a league. The real Stacked rule runs on the lightened stripe, so treat these as a proxy; the NHL-style swap then uses the other colour.

## OHL (20)

| Code | Team | Primary | Secondary | Third | Conf | Flags | Sources / notes |
|---|---|---|---|---|---|---|---|
| BAR | Barrie Colts | `#003A8A` | `#EC1B02` | `#FFD548` | medium |  | TCC lists grey first (ignored); Wikipedia navy #023986, red #E91B03, yellow #FED548 and logo agree on navy primary. Red vs yellow as secondary is a call. Sources: https://teamcolorcodes.com/barrie-colts-color-codes/ ; https://en.wikipedia.org/wiki/Barrie_Colts |
| BRAM | Brampton Steelheads | `#002768` | `#A8A9AC` | `#FFFFFF` | low |  | RELOCATED/REBRANDED (Mississauga -> Brampton, 2024). No brand guide or TCC page. Primary = SportsDB #002768, matches logo sample #012667; Wikipedia infobox #003E7E disagrees. Secondary silver sampled from logo (#A8A9AC; Wikipedia says silver). Sources: https://assets.leaguestat.com/ohl/logos/18.jpg ; https://www.thesportsdb.com/team/144347 ; TCC mississauga-steelheads (old: #1A2857/#8F9090) ; https://en.wikipedia.org/wiki/Brampton_Steelheads |
| BFD | Brantford Bulldogs | `#231F20` | `#B47F3A` | `#FFC627` | low |  | No TCC page for Brantford; used the Hamilton Bulldogs page (franchise moved 2015). Logo is dark brown/black with tan-gold (#BC844C); Wikipedia says black/gold #FFC627. Gold shade disagrees. Sources: https://teamcolorcodes.com/hamilton-bulldogs-color-codes/ ; https://en.wikipedia.org/wiki/Brantford_Bulldogs ; https://assets.leaguestat.com/ohl/logos/1.png |
| ER | Erie Otters | `#0A1E3F` | `#FEC52F` | `#A6192E` | high |  | TCC lists gold first; navy is the logo-dominant colour (gold primary goes to accent slot). Wikipedia #041E42/#FFB81C/#A6192E. Sources: https://teamcolorcodes.com/erie-otters-color-codes/ ; https://en.wikipedia.org/wiki/Erie_Otters |
| FLNT | Flint Firebirds | `#0C1D3C` | `#FC5D00` | `#B2B7BB` | medium |  | Navy differs (Wikipedia #002E62, logo #0C1C44 matches TCC); orange TCC #FC5D00 vs Wikipedia #F78429. Sources: https://teamcolorcodes.com/flint-firebirds-color-codes/ ; https://en.wikipedia.org/wiki/Flint_Firebirds |
| GUE | Guelph Storm | `#900027` | `#B3B5B8` | `#000000` | high |  | Wikipedia crimson #900028 + black; TCC silver second. Logo has black outlines and silver. Sources: https://teamcolorcodes.com/guelph-storm-color-codes/ ; https://en.wikipedia.org/wiki/Guelph_Storm |
| KGN | Kingston Frontenacs | `#000000` | `#FCBA32` | `#FFFFFF` | high | near-black primary | TCC lists gold first; black is the logo/jersey base (gold primary goes to accent slot). Near-black primary. Wikipedia #010101/#E0AD0D. Sources: https://teamcolorcodes.com/kingston-frontenacs-color-codes/ ; https://en.wikipedia.org/wiki/Kingston_Frontenacs |
| KIT | Kitchener Rangers | `#0033A0` | `#CF0A2C` | `#FFFFFF` | medium |  | TCC lists red first, Wikipedia blue first (#00529B/#EF3E42); logo blue-dominant (#243C94). Blue chosen as primary; blue hex disagrees. Sources: https://teamcolorcodes.com/kitchener-rangers-color-codes/ ; https://en.wikipedia.org/wiki/Kitchener_Rangers |
| LDN | London Knights | `#046A38` | `#FFB81C` | `#000000` | medium |  | Wikipedia border #046A38/#FFB81C matches logo green #046C3C. TCC green #27824D is lighter, TCC second = black (kept as tertiary). Sources: https://en.wikipedia.org/wiki/London_Knights ; https://teamcolorcodes.com/london-knights-color-codes/ |
| NIAG | Niagara IceDogs | `#BF0C26` | `#000000` | `#FFFFFF` | medium |  | Wikipedia #C60C30. Logo (id-only 20.png, possibly older mark) is red/black. Sources: https://teamcolorcodes.com/niagara-icedogs-color-codes/ ; https://en.wikipedia.org/wiki/Niagara_IceDogs |
| NB | North Bay Battalion | `#807A11` | `#000000` | `#FCD213` | medium | C1 white-on-primary 4.5 (<4.5) | Olive: TCC #807A11, logo #8C841C, Wikipedia khaki #6B8E23. Yellow could be the secondary instead of black. Sources: https://teamcolorcodes.com/north-bay-battalion-color-codes/ ; https://en.wikipedia.org/wiki/North_Bay_Battalion |
| OSH | Oshawa Generals | `#E21836` | `#06173C` | `#8A0E04` | low |  | TCC has red + dark red (#8A0E04) and no navy; Wikipedia red #C60C30 + blue #0039A6; logo lettering is navy. Secondary navy is SAMPLED from the logo (#06173C). Owner eye. Sources: https://teamcolorcodes.com/oshawa-generals-color-codes/ ; https://en.wikipedia.org/wiki/Oshawa_Generals ; https://assets.leaguestat.com/ohl/logos/4.png |
| OTT | Ottawa 67's | `#AC1F2D` | `#000000` | `#FFFFFF` | high |  | Logo sample #AC1C2C matches TCC; Wikipedia #C60C30 brighter. Not the NHL Senators (same code, different league). Sources: https://teamcolorcodes.com/ottawa-67s-color-codes/ ; https://en.wikipedia.org/wiki/Ottawa_67's |
| OS | Owen Sound Attack | `#BF2F38` | `#FFDD00` | `#000000` | low |  | TCC says yellow/black (no red); Wikipedia red/gold/black; logo has red lettering band, yellow bear, black outline. Primary order is a call. Owner eye. Sources: https://en.wikipedia.org/wiki/Owen_Sound_Attack ; https://teamcolorcodes.com/owen-sound-attack-color-codes/ |
| PBO | Peterborough Petes | `#75263D` | `#000000` | `#FFFFFF` | medium |  | Maroon: Wikipedia #75263D matches logo (#74243C, 92 percent of pixels). TCC #94002A is a redder crimson and disagrees. Sources: https://en.wikipedia.org/wiki/Peterborough_Petes_(ice_hockey) ; https://assets.leaguestat.com/ohl/logos/6.png ; https://teamcolorcodes.com/peterborough-petes-color-codes/ |
| SAG | Saginaw Spirit | `#003263` | `#BE2F38` | `#C9CBCC` | high |  | Wikipedia #00285D/#BF2F38; logo #043464. Sources: https://teamcolorcodes.com/saginaw-spirit-color-codes/ ; https://en.wikipedia.org/wiki/Saginaw_Spirit |
| SAR | Sarnia Sting | `#231F20` | `#FFC425` | `#FFFFFF` | high |  | Gold primary goes to accent slot; near-black primary. Sources: https://teamcolorcodes.com/sarnia-sting-color-codes/ ; https://en.wikipedia.org/wiki/Sarnia_Sting |
| SOO | Sault Ste. Marie Greyhounds | `#CF2029` | `#FFFFFF` |  | medium |  | Red hex disagrees (TCC #CF2029, Wikipedia #EF3E42, logo #E43C3C). White secondary. Sources: https://teamcolorcodes.com/sault-ste-marie-greyhounds-color-codes/ ; https://en.wikipedia.org/wiki/Sault_Ste._Marie_Greyhounds |
| SBY | Sudbury Wolves | `#000000` | `#B9BBBD` | `#D4242C` | low | near-black primary | TCC lists light grey first, then black; Wikipedia still says blue/white/grey (looks stale); logo is grey wolf, black outline, red mouth. Black chosen as base (a light grey primary fails white-on-primary). Owner eye. Sources: https://teamcolorcodes.com/sudbury-wolves-color-codes/ ; https://en.wikipedia.org/wiki/Sudbury_Wolves ; https://assets.leaguestat.com/ohl/logos/12.png |
| WSR | Windsor Spitfires | `#00274D` | `#E31B23` | `#FFFFFF` | high |  | Wikipedia #003153/#CF111B; logo #04244C. Sources: https://teamcolorcodes.com/windsor-spitfires-color-codes/ ; https://en.wikipedia.org/wiki/Windsor_Spitfires |

## WHL (23)

| Code | Team | Primary | Secondary | Third | Conf | Flags | Sources / notes |
|---|---|---|---|---|---|---|---|
| BDN | Brandon Wheat Kings | `#000000` | `#E6BF2E` | `#FFFFFF` | medium | near-black primary | TCC lists gold first; black base (gold goes to accent slot). Gold hex differs (Wikipedia #E7C02A/#D9C756). Sources: https://teamcolorcodes.com/brandon-wheat-kings-color-codes/ ; https://en.wikipedia.org/wiki/Brandon_Wheat_Kings |
| CGY | Calgary Hitmen | `#000000` | `#E13A3E` | `#9F7152` | medium | near-black primary | TCC lists red first; Wikipedia and the logo (black skull, red eyes) are black-first. Copper per Wikipedia. Not the NHL Flames. Sources: https://en.wikipedia.org/wiki/Calgary_Hitmen ; https://teamcolorcodes.com/calgary-hitmen-color-codes/ |
| EDM | Edmonton Oil Kings | `#00539C` | `#FFC526` | `#C61A31` | medium |  | TCC has yellow/green/red (green looks wrong); Wikipedia red/royal blue/gold; logo is blue + gold. Blue primary matches logo (#04549C). Not the NHL Oilers. Sources: https://en.wikipedia.org/wiki/Edmonton_Oil_Kings ; https://teamcolorcodes.com/edmonton-oil-kings-color-codes/ ; https://assets.leaguestat.com/whl/logos/228.png |
| EVT | Everett Silvertips | `#045B41` | `#BC8448` | `#A4ACAC` | medium |  | TCC lists bronze first; green is the logo base. Green hex differs (Wikipedia #154734, logo #144434). Sources: https://teamcolorcodes.com/everett-silvertips-color-codes/ ; https://en.wikipedia.org/wiki/Everett_Silvertips |
| KAM | Kamloops Blazers | `#032C62` | `#D0471D` | `#FFFFFF` | high |  | Logo #042C64/#D4441C matches TCC. Wikipedia navy #08007B looks off. Sources: https://teamcolorcodes.com/kamloops-blazers-color-codes/ ; https://en.wikipedia.org/wiki/Kamloops_Blazers |
| KEL | Kelowna Rockets | `#008394` | `#000000` | `#E13A3E` | medium | C1 white-on-primary 4.5 (<4.5) | Teal agreed (TCC=Wikipedia #008394). Second colour: TCC black, Wikipedia red #E13A3E, copper #B98544; logo has red and black. Sources: https://teamcolorcodes.com/kelowna-rockets-color-codes/ ; https://en.wikipedia.org/wiki/Kelowna_Rockets |
| LET | Lethbridge Hurricanes | `#CC1D30` | `#121942` | `#FFFFFF` | high |  | Wikipedia #CD172C/#0A1240. Sources: https://teamcolorcodes.com/lethbridge-hurricanes-color-codes/ ; https://en.wikipedia.org/wiki/Lethbridge_Hurricanes |
| MH | Medicine Hat Tigers | `#E56B1F` | `#000000` | `#FFFFFF` | high | C1 white-on-primary 3.3 (<4.5) | TCC = Wikipedia (#E56B1F/#231F20). Orange primary: white-on-primary contrast is low (see C1). Sources: https://teamcolorcodes.com/medicine-hat-tigers-color-codes/ ; https://en.wikipedia.org/wiki/Medicine_Hat_Tigers |
| MJ | Moose Jaw Warriors | `#CC0000` | `#000000` | `#FFFFFF` | medium |  | Red differs (Wikipedia #E2373C, logo #CC0404 matches TCC). Sources: https://teamcolorcodes.com/moose-jaw-warriors-color-codes/ ; https://en.wikipedia.org/wiki/Moose_Jaw_Warriors |
| PEN | Penticton Vees | `#25366C` | `#7AB2E0` | `#000000` | medium |  | NEW WHL FRANCHISE (ex-BCHL; 2022 BCHL rebrand). No TCC page. Wikipedia infobox navy #25366C, mountain blue #7AB2E0, black; logo is darker navy #0C1E42. Single hex source. Sources: https://en.wikipedia.org/wiki/Penticton_Vees_(WHL) ; https://assets.leaguestat.com/whl/logos/277.png |
| POR | Portland Winterhawks | `#000000` | `#CC202E` | `#7899AE` | medium | near-black primary | TCC lists red first; Wikipedia (black, red, gray, gold) and logo are black-first. Sources: https://teamcolorcodes.com/portland-winterhawks-color-codes/ ; https://en.wikipedia.org/wiki/Portland_Winterhawks |
| PA | Prince Albert Raiders | `#0D843E` | `#231501` | `#C4B684` | medium |  | Wikipedia green #007940, black, tan #C4B684; TCC second is near-black brown. Sources: https://teamcolorcodes.com/prince-albert-raiders-color-codes/ ; https://en.wikipedia.org/wiki/Prince_Albert_Raiders |
| PG | Prince George Cougars | `#D0202E` | `#D0A176` | `#6A0000` | medium |  | Bronze/beige is the second colour (Wikipedia #9E7053, TCC #D0A176); dark red #6A0000 tertiary. Sources: https://teamcolorcodes.com/prince-george-cougars-color-codes/ ; https://en.wikipedia.org/wiki/Prince_George_Cougars |
| RD | Red Deer Rebels | `#900028` | `#000000` | `#A8B3B8` | high |  | Wikipedia #910023. Sources: https://teamcolorcodes.com/red-deer-rebels-color-codes/ ; https://en.wikipedia.org/wiki/Red_Deer_Rebels |
| REG | Regina Pats | `#1A3668` | `#EF3342` | `#FFFFFF` | high |  | Wikipedia lists red first, but navy is the jersey/logo base (#193768). Sources: https://teamcolorcodes.com/regina-pats-color-codes/ ; https://en.wikipedia.org/wiki/Regina_Pats |
| SAS | Saskatoon Blades | `#262266` | `#FFCD36` | `#FFFFFF` | medium |  | TCC=Wikipedia hex values; logo blue is a purer blue (#0404AC). Gold goes to accent slot. Sources: https://teamcolorcodes.com/saskatoon-blades-color-codes/ ; https://en.wikipedia.org/wiki/Saskatoon_Blades |
| SEA | Seattle Thunderbirds | `#003876` | `#008357` | `#FFFFFF` | high |  | Wikipedia #00285D/#00843D. Not the NHL Kraken. Sources: https://teamcolorcodes.com/seattle-thunderbirds-color-codes/ ; https://en.wikipedia.org/wiki/Seattle_Thunderbirds |
| SPO | Spokane Chiefs | `#C41230` | `#013A81` | `#FFFFFF` | medium |  | Red-first by TCC and logo; Wikipedia red #E03A3E, blue #0039A6. Sources: https://teamcolorcodes.com/spokane-chiefs-color-codes/ ; https://en.wikipedia.org/wiki/Spokane_Chiefs |
| SC | Swift Current Broncos | `#026937` | `#001F5B` | `#FFFFFF` | medium |  | Wikipedia lists blue first; logo is green-dominant. Green and navy both very dark: see flags. Sources: https://teamcolorcodes.com/swift-current-broncos-color-codes/ ; https://en.wikipedia.org/wiki/Swift_Current_Broncos |
| TC | Tri-City Americans | `#0D1D41` | `#AC1A2D` | `#BEC0C2` | medium |  | Navy hex differs (Wikipedia #002D62, logo #042C64). Navy primary. Sources: https://teamcolorcodes.com/tri-city-americans-color-codes/ ; https://en.wikipedia.org/wiki/Tri-City_Americans |
| VAN | Vancouver Giants | `#231F20` | `#97002E` | `#B5B7B9` | medium |  | TCC lists grey first; black base. Wikipedia red #C60C30 brighter. Not the NHL Canucks. Sources: https://teamcolorcodes.com/vancouver-giants-color-codes/ ; https://en.wikipedia.org/wiki/Vancouver_Giants |
| VIC | Victoria Royals | `#013974` | `#A0A9AD` | `#D2040E` | medium |  | Royal blue hex differs (Wikipedia #182957, logo #1C2C5C). Silver second per Wikipedia and logo; TCC has black + red. Sources: https://teamcolorcodes.com/victoria-royals-color-codes/ ; https://en.wikipedia.org/wiki/Victoria_Royals |
| WEN | Wenatchee Wild | `#002F87` | `#231F20` | `#026FA8` | medium |  | RELOCATED (2023; ex-Winnipeg Ice franchise). Blue hex differs (Wikipedia #0047AB, logo #1C3C7C). Sources: https://teamcolorcodes.com/wenatchee-wild-color-codes/ ; https://en.wikipedia.org/wiki/Wenatchee_Wild |

## QMJHL (18)

| Code | Team | Primary | Secondary | Third | Conf | Flags | Sources / notes |
|---|---|---|---|---|---|---|---|
| BAC | Baie-Comeau Drakkar | `#BF2F38` | `#FFCC02` | `#FFFFFF` | medium |  | TCC says yellow/black (no red), but Wikipedia and logo are red-dominant. Red hex from Wikipedia (logo brighter, #EC1C24). Sources: https://en.wikipedia.org/wiki/Baie-Comeau_Drakkar ; https://teamcolorcodes.com/baie-comeau-drakkar-color-codes/ |
| BLB | Blainville-Boisbriand Armada | `#000000` | `#FFFFFF` |  | high | near-black primary | Black and white only, logo and both sources agree. Sources: https://teamcolorcodes.com/blainville-boisbriand-armada-color-codes/ ; https://en.wikipedia.org/wiki/Blainville-Boisbriand_Armada |
| CAP | Cape Breton Eagles | `#23282B` | `#FAB406` | `#FFFFFF` | medium |  | TCC and Wikipedia agree on charcoal + gold; the (id-only, possibly older) logo shows almost no gold. Sources: https://teamcolorcodes.com/cape-breton-eagles-color-codes/ ; https://en.wikipedia.org/wiki/Cape_Breton_Eagles |
| CHA | Charlottetown Islanders | `#000000` | `#A8996E` | `#FFFFFF` | medium | near-black primary | TCC lists beige first; black base. Gold shade: TCC #A8996E, Wikipedia #D0BD7F, logo tan #8C6C4C. Sources: https://teamcolorcodes.com/charlottetown-islanders-color-codes/ ; https://en.wikipedia.org/wiki/Charlottetown_Islanders |
| CHI | Chicoutimi Saguenéens | `#003263` | `#3AB4E7` | `#FFFFFF` | low |  | Navy hex disagrees: TCC #003263, Wikipedia #003E7E, logo lettering indigo #1C1556. Light blue: TCC #3AB4E7, Wikipedia #99BADD, logo #A4DCFC. Not the NHL Blackhawks. Sources: https://teamcolorcodes.com/chicoutimi-sagueneens-color-codes/ ; https://en.wikipedia.org/wiki/Chicoutimi_Saguenéens |
| DRU | Drummondville Voltigeurs | `#000000` | `#EE3224` | `#FFFFFF` | medium | near-black primary | TCC lists red first; Wikipedia and logo are black-first. Sources: https://en.wikipedia.org/wiki/Drummondville_Voltigeurs ; https://teamcolorcodes.com/drummondville-voltigeurs-color-codes/ |
| GAT | Gatineau Olympiques | `#000000` | `#717073` | `#FFFFFF` | medium | near-black primary | TCC lists grey #717073 first; black chosen as base (Wikipedia black-first). Alternative: grey primary. Sources: https://teamcolorcodes.com/gatineau-olympiques-color-codes/ ; https://en.wikipedia.org/wiki/Gatineau_Olympiques |
| HAL | Halifax Mooseheads | `#174734` | `#DEB408` | `#E31837` | low |  | Green SAMPLED from the logo (#174734; Wikipedia #004F00); TCC has no green (lists gold/black/red). Gold/red from TCC. Sources: https://assets.leaguestat.com/lhjmq/logos/5.png ; https://teamcolorcodes.com/halifax-mooseheads-color-codes/ ; https://en.wikipedia.org/wiki/Halifax_Mooseheads |
| MON | Moncton Wildcats | `#C60C30` | `#FDC13D` | `#000000` | medium |  | TCC lists gold/black (no red); Wikipedia red-first with gold #FDB827; logo red-bordered gold cat. Red hex from Wikipedia (logo #D41C3C). Sources: https://en.wikipedia.org/wiki/Moncton_Wildcats ; https://teamcolorcodes.com/moncton-wildcats-color-codes/ |
| NFL | Newfoundland Regiment | `#0E2342` | `#8B2535` | `#DFD2A6` | medium |  | NEW FRANCHISE (relocated Acadie-Bathurst Titan, name revealed Jan 2025). Puttee blue/legacy maroon/heritage tan from the Wikipedia infobox, confirmed by logo samples (#0A2240/#8C2434). TCC page is the ECHL Growlers, not used. Team code NFL is a team, not a league. Sources: https://en.wikipedia.org/wiki/Newfoundland_Regiment_(ice_hockey) ; https://assets.leaguestat.com/lhjmq/logos/2.png |
| QUE | Québec Remparts | `#E31836` | `#000000` | `#FFFFFF` | medium |  | Logo is mostly black/white badge; red is the brand primary per TCC and Wikipedia (#C60C30). Sources: https://teamcolorcodes.com/quebec-remparts-color-codes/ ; https://en.wikipedia.org/wiki/Quebec_Remparts |
| RIM | Rimouski Océanic | `#002243` | `#ACB2B5` | `#ABD8C0` | low |  | Navy SAMPLED from logo (#002243); TCC #18124B (purple-navy), Wikipedia #00529B/#003E7E all disagree. Silver sampled; TCC second is a pale mint #ABD8C0 (kept as tertiary). Sources: https://assets.leaguestat.com/lhjmq/logos/18.png ; https://teamcolorcodes.com/rimouski-oceanic-color-codes/ ; https://en.wikipedia.org/wiki/Rimouski_Océanic |
| ROU | Rouyn-Noranda Huskies | `#000000` | `#ED1C2E` | `#FFFFFF` | medium | near-black primary | TCC lists red first; Wikipedia and logo are black-first (black/white wolf, red outline). Sources: https://teamcolorcodes.com/rouyn-noranda-huskies-color-codes/ ; https://en.wikipedia.org/wiki/Rouyn-Noranda_Huskies |
| SNB | Saint John Sea Dogs | `#004C97` | `#000000` | `#8D9093` | medium |  | Wikipedia lists brand names (Sea Dogs Blue #004C97, Harbour Seal Black, Championship Pewter, Brick Red #C8102E). TCC lists grey first, blue #4274A6 differs. Sources: https://en.wikipedia.org/wiki/Saint_John_Sea_Dogs ; https://teamcolorcodes.com/saint-john-sea-dogs-color-codes/ |
| SHA | Shawinigan Cataractes | `#002D62` | `#FFC425` | `#000000` | medium |  | TCC lists gold first; navy chosen as primary (Wikipedia navy #003E7E, logo #042C64). Sources: https://teamcolorcodes.com/shawinigan-cataractes-color-codes/ ; https://en.wikipedia.org/wiki/Shawinigan_Cataractes |
| SHE | Sherbrooke Phœnix | `#051B54` | `#99BADD` | `#FFFFFF` | medium |  | Navy: TCC #051B54 = Wikipedia #041A54. Light blue from Wikipedia only (TCC second is cream #F8F2D7). Logo navy/white. Sources: https://teamcolorcodes.com/sherbrooke-phoenix-color-codes/ ; https://en.wikipedia.org/wiki/Sherbrooke_Phoenix |
| VDO | Val-d'Or Foreurs | `#005837` | `#DDB208` | `#000000` | medium |  | TCC lists gold first; green is the logo base (#045C44; Wikipedia #005837). Green hex from Wikipedia. Sources: https://teamcolorcodes.com/val-dor-foreurs-color-codes/ ; https://en.wikipedia.org/wiki/Val-d'Or_Foreurs |
| VIC | Victoriaville Tigres | `#000000` | `#FCB827` | `#F4812A` | high | near-black primary | Gold primary goes to accent slot; near-black base. Same code VIC as the WHL Victoria Royals (different league). Sources: https://teamcolorcodes.com/victoriaville-tigres-color-codes/ ; https://en.wikipedia.org/wiki/Victoriaville_Tigres |

## Look-alike primaries (ΔE76 < 20, same league)

103 pairs: OHL 27, WHL 39, QMJHL 37. Most come from clusters (black/near-black, navy, red). Pairs where no primary/secondary swap reaches ΔE >= 20: none.

| League | A | B | A primary | B primary | dE76 |
|---|---|---|---|---|---|
| OHL | BFD | SAR | `#231F20` | `#231F20` | 0.0 |
| OHL | KGN | SBY | `#000000` | `#000000` | 0.0 |
| OHL | ER | FLNT | `#0A1E3F` | `#0C1D3C` | 1.5 |
| OHL | ER | WSR | `#0A1E3F` | `#00274D` | 5.6 |
| OHL | OTT | OS | `#AC1F2D` | `#BF2F38` | 5.8 |
| OHL | NIAG | SOO | `#BF0C26` | `#CF2029` | 6.1 |
| OHL | FLNT | WSR | `#0C1D3C` | `#00274D` | 6.8 |
| OHL | OSH | SOO | `#E21836` | `#CF2029` | 8.1 |
| OHL | SAG | WSR | `#003263` | `#00274D` | 8.4 |
| OHL | NIAG | OS | `#BF0C26` | `#BF2F38` | 10.6 |
| OHL | GUE | OTT | `#900027` | `#AC1F2D` | 11.1 |
| OHL | NIAG | OSH | `#BF0C26` | `#E21836` | 11.3 |
| OHL | BAR | BRAM | `#003A8A` | `#002768` | 11.5 |
| OHL | NIAG | OTT | `#BF0C26` | `#AC1F2D` | 12.1 |
| OHL | BFD | KGN | `#231F20` | `#000000` | 12.4 |
| OHL | BFD | SBY | `#231F20` | `#000000` | 12.4 |
| OHL | KGN | SAR | `#000000` | `#231F20` | 12.4 |
| OHL | SAR | SBY | `#231F20` | `#000000` | 12.4 |
| OHL | ER | SAG | `#0A1E3F` | `#003263` | 13.3 |
| OHL | BRAM | SAG | `#002768` | `#003263` | 13.7 |
| OHL | OS | SOO | `#BF2F38` | `#CF2029` | 13.9 |
| OHL | FLNT | SAG | `#0C1D3C` | `#003263` | 14.7 |
| OHL | GUE | OS | `#900027` | `#BF2F38` | 16.4 |
| OHL | OTT | SOO | `#AC1F2D` | `#CF2029` | 17.0 |
| OHL | OSH | OS | `#E21836` | `#BF2F38` | 18.0 |
| OHL | BRAM | WSR | `#002768` | `#00274D` | 19.2 |
| OHL | BAR | KIT | `#003A8A` | `#0033A0` | 19.3 |
| QMJHL | BLB | CHA | `#000000` | `#000000` | 0.0 |
| QMJHL | BLB | DRU | `#000000` | `#000000` | 0.0 |
| QMJHL | BLB | GAT | `#000000` | `#000000` | 0.0 |
| QMJHL | BLB | ROU | `#000000` | `#000000` | 0.0 |
| QMJHL | BLB | VIC | `#000000` | `#000000` | 0.0 |
| QMJHL | CHA | DRU | `#000000` | `#000000` | 0.0 |
| QMJHL | CHA | GAT | `#000000` | `#000000` | 0.0 |
| QMJHL | CHA | ROU | `#000000` | `#000000` | 0.0 |
| QMJHL | CHA | VIC | `#000000` | `#000000` | 0.0 |
| QMJHL | DRU | GAT | `#000000` | `#000000` | 0.0 |
| QMJHL | DRU | ROU | `#000000` | `#000000` | 0.0 |
| QMJHL | DRU | VIC | `#000000` | `#000000` | 0.0 |
| QMJHL | GAT | ROU | `#000000` | `#000000` | 0.0 |
| QMJHL | GAT | VIC | `#000000` | `#000000` | 0.0 |
| QMJHL | ROU | VIC | `#000000` | `#000000` | 0.0 |
| QMJHL | NFL | RIM | `#0E2342` | `#002243` | 2.5 |
| QMJHL | CHI | SHA | `#003263` | `#002D62` | 4.2 |
| QMJHL | BAC | MON | `#BF2F38` | `#C60C30` | 10.0 |
| QMJHL | MON | QUE | `#C60C30` | `#E31836` | 10.4 |
| QMJHL | SHA | SHE | `#002D62` | `#051B54` | 10.4 |
| QMJHL | CHI | RIM | `#003263` | `#002243` | 12.3 |
| QMJHL | CHI | NFL | `#003263` | `#0E2342` | 13.2 |
| QMJHL | HAL | VDO | `#174734` | `#005837` | 13.5 |
| QMJHL | RIM | SHA | `#002243` | `#002D62` | 14.1 |
| QMJHL | CHI | SHE | `#003263` | `#051B54` | 14.3 |
| QMJHL | NFL | SHA | `#0E2342` | `#002D62` | 15.1 |
| QMJHL | BLB | CAP | `#000000` | `#23282B` | 16.0 |
| QMJHL | CAP | CHA | `#23282B` | `#000000` | 16.0 |
| QMJHL | CAP | DRU | `#23282B` | `#000000` | 16.0 |
| QMJHL | CAP | GAT | `#23282B` | `#000000` | 16.0 |
| QMJHL | CAP | ROU | `#23282B` | `#000000` | 16.0 |
| QMJHL | CAP | VIC | `#23282B` | `#000000` | 16.0 |
| QMJHL | SNB | SHA | `#004C97` | `#002D62` | 17.8 |
| QMJHL | BAC | QUE | `#BF2F38` | `#E31836` | 18.4 |
| QMJHL | CHI | SNB | `#003263` | `#004C97` | 18.5 |
| QMJHL | RIM | SHE | `#002243` | `#051B54` | 18.9 |
| QMJHL | NFL | SHE | `#0E2342` | `#051B54` | 19.7 |
| WHL | BDN | CGY | `#000000` | `#000000` | 0.0 |
| WHL | BDN | POR | `#000000` | `#000000` | 0.0 |
| WHL | CGY | POR | `#000000` | `#000000` | 0.0 |
| WHL | SEA | VIC | `#003876` | `#013974` | 2.2 |
| WHL | LET | PG | `#CC1D30` | `#D0202E` | 2.7 |
| WHL | LET | SPO | `#CC1D30` | `#C41230` | 3.8 |
| WHL | PEN | REG | `#25366C` | `#1A3668` | 3.9 |
| WHL | PEN | VIC | `#25366C` | `#013974` | 5.7 |
| WHL | KAM | PEN | `#032C62` | `#25366C` | 5.8 |
| WHL | KAM | REG | `#032C62` | `#1A3668` | 5.8 |
| WHL | REG | VIC | `#1A3668` | `#013974` | 6.1 |
| WHL | KAM | VIC | `#032C62` | `#013974` | 6.3 |
| WHL | PG | SPO | `#D0202E` | `#C41230` | 6.5 |
| WHL | PEN | SEA | `#25366C` | `#003876` | 6.6 |
| WHL | KAM | SEA | `#032C62` | `#003876` | 6.9 |
| WHL | REG | SEA | `#1A3668` | `#003876` | 7.8 |
| WHL | BDN | VAN | `#000000` | `#231F20` | 12.4 |
| WHL | CGY | VAN | `#000000` | `#231F20` | 12.4 |
| WHL | POR | VAN | `#000000` | `#231F20` | 12.4 |
| WHL | EDM | SEA | `#00539C` | `#003876` | 12.6 |
| WHL | EDM | VIC | `#00539C` | `#013974` | 13.1 |
| WHL | KAM | TC | `#032C62` | `#0D1D41` | 13.1 |
| WHL | REG | TC | `#1A3668` | `#0D1D41` | 13.8 |
| WHL | SAS | WEN | `#262266` | `#002F87` | 14.0 |
| WHL | KAM | SAS | `#032C62` | `#262266` | 14.1 |
| WHL | PEN | SAS | `#25366C` | `#262266` | 14.6 |
| WHL | PA | SC | `#0D843E` | `#026937` | 14.9 |
| WHL | SAS | SEA | `#262266` | `#003876` | 14.9 |
| WHL | EVT | SC | `#045B41` | `#026937` | 15.7 |
| WHL | PEN | TC | `#25366C` | `#0D1D41` | 15.7 |
| WHL | SAS | VIC | `#262266` | `#013974` | 16.4 |
| WHL | EDM | PEN | `#00539C` | `#25366C` | 16.8 |
| WHL | EDM | REG | `#00539C` | `#1A3668` | 17.8 |
| WHL | REG | SAS | `#1A3668` | `#262266` | 17.9 |
| WHL | MJ | PG | `#CC0000` | `#D0202E` | 18.2 |
| WHL | TC | VIC | `#0D1D41` | `#013974` | 18.6 |
| WHL | SEA | WEN | `#003876` | `#002F87` | 18.8 |
| WHL | EDM | KAM | `#00539C` | `#032C62` | 19.3 |
| WHL | SEA | TC | `#003876` | `#0D1D41` | 19.7 |
