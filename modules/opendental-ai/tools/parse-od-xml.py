#!/usr/bin/env python3
"""
One-shot generator: convert OpenDental's published XML schema documentation
into a compact JSON catalog shipped as a classpath resource with the
opendental-ai module.

Usage (from this directory):
    python parse-od-xml.py OpenDentalDocumentation26-1.xml \
        ../src/main/resources/schema/opendental-26.1.json

The resulting JSON is read at startup by SchemaCatalog.java.
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def clean(text):
    if text is None:
        return ""
    return re.sub(r"\s+", " ", text).strip()


def parse(xml_path: Path) -> list[dict]:
    tree = ET.parse(xml_path)
    root = tree.getroot()
    version = root.attrib.get("version", "unknown")

    tables = []
    for t in root.findall("table"):
        table = {
            "name": t.attrib["name"],
            "summary": clean((t.find("summary") or ET.Element("x")).text),
            "columns": [],
        }
        for c in t.findall("column"):
            col = {
                "name": c.attrib["name"],
                "type": c.attrib.get("type", ""),
                "order": int(c.attrib.get("order", -1)),
                "summary": clean((c.find("summary") or ET.Element("x")).text),
            }
            # Pull out enumeration hints (helps the LLM write WHERE clauses).
            enum = c.find("Enumeration")
            if enum is not None:
                values = []
                for v in enum.findall("EnumValue"):
                    raw = (v.text or "").strip()
                    # Text can be "0" or "0 - Direct" — take the leading integer.
                    m = re.match(r"^(-?\d+)", raw)
                    values.append({
                        "name": v.attrib.get("name", ""),
                        "value": int(m.group(1)) if m else None,
                        "raw": raw if not m or raw != m.group(1) else None,
                    })
                col["enum"] = {"name": enum.attrib.get("name", ""), "values": values}
            table["columns"].append(col)
        table["columns"].sort(key=lambda x: x["order"])
        tables.append(table)

    return {
        "source": "https://www.opendental.com/OpenDentalDocumentation26-1.xml",
        "version": version,
        "table_count": len(tables),
        "tables": tables,
    }


def main():
    if len(sys.argv) != 3:
        print("usage: parse-od-xml.py <input.xml> <output.json>", file=sys.stderr)
        sys.exit(2)
    xml_path = Path(sys.argv[1])
    out_path = Path(sys.argv[2])

    catalog = parse(xml_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2, ensure_ascii=False)

    print(
        f"wrote {out_path} · version={catalog['version']} · tables={catalog['table_count']}"
    )


if __name__ == "__main__":
    main()
