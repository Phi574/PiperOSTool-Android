"""Import the user-supplied dataPhone.xlsx without dropping any listed device."""
import json
import sys
from datetime import datetime
from pathlib import Path

import openpyxl


def value(cell):
    item = cell.value
    if item is None:
        return ""
    if isinstance(item, datetime):
        if cell.number_format == "d-mmm":
            return f"{item.day}-{item.month}"
        if cell.number_format == "mmm-yy":
            return f"{item.month}-{item.year % 100}"
        return item.date().isoformat()
    return str(item).strip()


def main():
    source, destination = map(Path, sys.argv[1:3])
    sheet = openpyxl.load_workbook(source, data_only=True).active
    headings = []
    devices = []
    for row in sheet:
        cells = [value(cell) for cell in row]
        if cells[0] in {"Hãng", "Platform"}:
            headings = cells
            continue
        if not cells[0]:
            continue
        raw = {title: data for title, data in zip(headings, cells) if title and data}
        devices.append({"row": row[0].row, "brand": cells[0], "series": cells[1],
                        "name": cells[2], "fields": raw})
    assert len(devices) == 1307, len(devices)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(devices, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Imported {len(devices)} entries from {source.name} to {destination}")


if __name__ == "__main__":
    main()
