# App launcher icons

`make_app_icon.py` renders an app's launcher icon: the GoldSky logo
(`core/ui/src/main/res/drawable/goldsky_logo.png`) with the app's word mark in
Roboto Flex Black (open licence, ships with Android Studio) overlaid at the
logo's bottom-right, on black. It checks that everything stays inside the
adaptive icon's 66dp safe circle.

```
python make_app_icon.py <goldsky_logo.png> <RobotoFlex-Regular.ttf> <out_dir> <WORD> <#RRGGBB>
```

| App   | WORD  | colour    |
|-------|-------|-----------|
| Iris  | IRIS  | `#00E5FF` |
| Ourea | OUREA | `#FFD54F` |
| Ourea Eye | EYE | `#FFD54F` (Ourea's gold: it is Ourea's customer display) |

It writes `ic_launcher_foreground.png` (copy it to `src/main/res/drawable/`),
`ic_launcher.png` / `ic_launcher_round.png` (legacy icons for API 25, copy them
to `src/main/res/mipmap/`) and `preview.png`. Each app also has
`drawable/ic_launcher_background.xml` (black) and
`mipmap-anydpi-v26/ic_launcher(_round).xml`.
