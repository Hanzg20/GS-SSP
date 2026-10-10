# Iris launcher icon

`make_iris_icon.py` renders the icon from the GoldSky logo
(`core/ui/src/main/res/drawable/goldsky_logo.png`) plus an "IRIS" word mark in
Roboto Flex (open licence, ships with Android Studio):

```
python make_iris_icon.py <goldsky_logo.png> <RobotoFlex-Regular.ttf> <out_dir>
```

It writes `ic_launcher_foreground.png` (adaptive foreground, content inside the
66dp safe zone), `ic_launcher.png` / `ic_launcher_round.png` (legacy, for API 25)
and `preview.png`. Copy the first into `src/main/res/drawable/`, the next two
into `src/main/res/mipmap/`.
