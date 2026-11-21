"""Check every app palette in both light and dark resource configurations."""

from pathlib import Path
import xml.etree.ElementTree as ET


RES = Path(__file__).resolve().parents[1] / "app/src/main/res"


def luminance(color):
    channels = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4 for c in channels]
    return sum(c * weight for c, weight in zip(linear, (.2126, .7152, .0722)))


def contrast(first, second):
    low, high = sorted((luminance(first), luminance(second)))
    return (high + .05) / (low + .05)


def main():
    checks = 0
    for folder in ("values", "values-night"):
        colors = {
            entry.attrib["name"]: entry.text
            for file in (RES / folder / "colors.xml", RES / folder / "palettes.xml")
            for entry in ET.parse(file).getroot()
            if entry.tag == "color"
        }
        for prefix in ("", "blue_", "brown_"):
            for ink, paper in (
                ("ink", "background"), ("ink", "surface"),
                ("muted", "background"), ("muted", "surface"),
                ("muted", "soft"), ("primary", "soft"),
                ("translation", "soft"), ("background", "primary"),
            ):
                value = contrast(colors[prefix + ink], colors[prefix + paper])
                assert value >= 4.5, (folder, prefix or "green", ink, paper, round(value, 2))
                checks += 1
    print(f"PASS: {checks} palette text contrast checks across 3 palettes and 2 modes")


if __name__ == "__main__":
    main()
