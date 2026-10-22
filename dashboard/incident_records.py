"""The only captured-data disk format. Live diagnostics must never use this store."""
from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import re
import tempfile


RECORDING_POLICY = "incident_fields_only_v1"
INCIDENT_FIELDS = frozenset({
    "involvedAccount", "flaggedAccount", "occurredAt", "scamMessage", "scamType", "scamCount",
})
INVALID_RECORD = "Incident rejected: use only approved fields with valid values."


def bounded_text(value, limit, multiline=False):
    if type(value) is not str or not value.strip() or len(value) > limit:
        raise ValueError(INVALID_RECORD)
    if any(not char.isprintable() and not (multiline and char in "\n\t") for char in value):
        raise ValueError(INVALID_RECORD)
    return value


def incident_record(value):
    """Reject the whole record on unknown fields or invalid values; never log input.

    This validates structure, not truth. Producers must supply an identified scam
    message, account evidence, and confirmed counts. Full OCR is not a substitute.
    Missing facts stay absent. No current Android event supplies this record.
    """
    if type(value) is not dict or not value or not value.keys() <= INCIDENT_FIELDS:
        raise ValueError(INVALID_RECORD)
    record = {}
    for key, item in value.items():
        if key in {"involvedAccount", "flaggedAccount"}:
            if type(item) is not dict or item.keys() != {"platform", "id"}:
                raise ValueError(INVALID_RECORD)
            record[key] = {"platform": bounded_text(item["platform"], 64),
                           "id": bounded_text(item["id"], 256)}
        elif key == "scamMessage":
            record[key] = bounded_text(item, 4096, multiline=True)
        elif key == "scamType":
            record[key] = bounded_text(item, 80)
        elif key == "occurredAt":
            if type(item) is not str or not re.fullmatch(
                    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})", item):
                raise ValueError(INVALID_RECORD)
            try:
                datetime.fromisoformat(item.replace("Z", "+00:00"))
            except ValueError:
                raise ValueError(INVALID_RECORD) from None
            record[key] = item
        elif key == "scamCount":
            # bool is an int subclass. It is not a confirmed incident count.
            if type(item) is not int or not 0 <= item <= 1_000_000 or "involvedAccount" not in value:
                raise ValueError(INVALID_RECORD)
            record[key] = item
    return record


class IncidentStore:
    """One validated file, with no run IDs, diagnostics, scripts, or sidecar logs."""

    def __init__(self, directory: Path):
        self.directory = directory.resolve()
        self.path = self.directory / "incidents-v1.json"

    def read(self):
        if self.path.is_symlink():
            raise ValueError("Incident storage is unavailable.")
        if not self.path.exists():
            return []
        try:
            records = json.loads(self.path.read_text(encoding="utf-8"))
            if type(records) is not list:
                raise ValueError(INVALID_RECORD)
            return [incident_record(record) for record in records]
        except (ValueError, UnicodeError):
            # Reject the entire file. Do not expose its content or salvage legacy records.
            raise ValueError("Incident storage contains an invalid record. No data was loaded or exported.") from None

    def append(self, value):
        record = incident_record(value)
        records = self.read()
        records.append(record)
        payload = json.dumps(records, ensure_ascii=False, indent=2).encode("utf-8")
        self.directory.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode="wb", prefix=".incidents-", suffix=".tmp",
                                             dir=self.directory, delete=False) as output:
                temporary = Path(output.name)
                output.write(payload)
            temporary.replace(self.path)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        return len(records)
