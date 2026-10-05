"""Store development API keys in the current user's Windows Credential Manager."""
from __future__ import annotations

import ctypes
from ctypes import wintypes
import hashlib
import os
import re


_CRED_TYPE_GENERIC = 1
_CRED_PERSIST_LOCAL_MACHINE = 2
_ERROR_NOT_FOUND = 1168
_MAX_KEY_BYTES = 2048
_KEY = re.compile(r"[!-~]{1,2048}", re.ASCII)
_SERIAL = re.compile(r"[!-~]{1,1024}", re.ASCII)


class CredentialStoreError(Exception):
    """A safe error message that does not contain a credential or OS error text."""


class _Credential(ctypes.Structure):
    # Native alignment and pointer fields support both 32-bit and 64-bit Python.
    _fields_ = [
        ("Flags", wintypes.DWORD),
        ("Type", wintypes.DWORD),
        ("TargetName", wintypes.LPWSTR),
        ("Comment", wintypes.LPWSTR),
        ("LastWritten", wintypes.FILETIME),
        ("CredentialBlobSize", wintypes.DWORD),
        ("CredentialBlob", ctypes.POINTER(ctypes.c_ubyte)),
        ("Persist", wintypes.DWORD),
        ("AttributeCount", wintypes.DWORD),
        ("Attributes", ctypes.c_void_p),
        ("TargetAlias", wintypes.LPWSTR),
        ("UserName", wintypes.LPWSTR),
    ]


_CredentialPointer = ctypes.POINTER(_Credential)
_api = None
if os.name == "nt":
    try:
        _api = ctypes.WinDLL("advapi32", use_last_error=True)
        _api.CredReadW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                   ctypes.POINTER(_CredentialPointer)]
        _api.CredReadW.restype = wintypes.BOOL
        _api.CredWriteW.argtypes = [_CredentialPointer, wintypes.DWORD]
        _api.CredWriteW.restype = wintypes.BOOL
        _api.CredDeleteW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD]
        _api.CredDeleteW.restype = wintypes.BOOL
        _api.CredFree.argtypes = [ctypes.c_void_p]
        _api.CredFree.restype = None
    except (OSError, AttributeError):
        _api = None

AVAILABLE = _api is not None


def _target(serial: str, provider: str) -> str:
    if not AVAILABLE:
        raise CredentialStoreError("Windows Credential Manager is unavailable.")
    if not isinstance(serial, str) or not _SERIAL.fullmatch(serial):
        raise CredentialStoreError("A valid device serial is required to store keys.")
    if provider not in ("jev", "openai"):
        raise CredentialStoreError("The key provider is not supported.")
    # Windows compares targets without case. Hash the exact serial to keep devices distinct.
    device = hashlib.sha256(serial.encode("ascii")).hexdigest()
    return f"Antiscam.Dev/{device}/{provider}"


def _read_credential(serial: str, provider: str):
    target = _target(serial, provider)
    credential = _CredentialPointer()
    try:
        found = _api.CredReadW(target, _CRED_TYPE_GENERIC, 0, ctypes.byref(credential))
    except (OSError, ctypes.ArgumentError):
        raise CredentialStoreError("Windows could not read the saved key.") from None
    if not found:
        if ctypes.get_last_error() == _ERROR_NOT_FOUND:
            return None
        raise CredentialStoreError("Windows could not read the saved key.")
    return credential


def saved(serial: str, provider: str) -> bool:
    """Check whether this device has a saved key without copying its contents."""
    credential = _read_credential(serial, provider)
    if credential is None:
        return False
    try:
        return bool(credential.contents.CredentialBlobSize)
    finally:
        _api.CredFree(credential)


def read(serial: str, provider: str) -> str | None:
    """Read a key for transfer to the selected device. Do not log the result."""
    credential = _read_credential(serial, provider)
    if credential is None:
        return None
    try:
        item = credential.contents
        if not 1 <= item.CredentialBlobSize <= _MAX_KEY_BYTES or not item.CredentialBlob:
            raise CredentialStoreError("The saved key is invalid. Save the key again.")
        try:
            value = ctypes.string_at(item.CredentialBlob, item.CredentialBlobSize).decode("ascii")
        except UnicodeDecodeError:
            raise CredentialStoreError("The saved key is invalid. Save the key again.") from None
        if not _KEY.fullmatch(value):
            raise CredentialStoreError("The saved key is invalid. Save the key again.")
        return value
    finally:
        _api.CredFree(credential)


def save(serial: str, provider: str, value: str) -> None:
    """Save one key for the current Windows user on this PC."""
    target = _target(serial, provider)
    if not isinstance(value, str) or not _KEY.fullmatch(value):
        raise CredentialStoreError("The key must contain 1 to 2048 ASCII characters without spaces.")
    encoded = bytearray(value, "ascii")
    blob = (ctypes.c_ubyte * len(encoded)).from_buffer(encoded)
    credential = _Credential()
    credential.Type = _CRED_TYPE_GENERIC
    credential.TargetName = target
    credential.Comment = "Antiscam development API key"
    credential.CredentialBlobSize = len(encoded)
    credential.CredentialBlob = ctypes.cast(blob, ctypes.POINTER(ctypes.c_ubyte))
    credential.Persist = _CRED_PERSIST_LOCAL_MACHINE
    credential.UserName = provider
    try:
        if not _api.CredWriteW(ctypes.byref(credential), 0):
            raise CredentialStoreError("Windows could not save the key.")
    except (OSError, ctypes.ArgumentError):
        raise CredentialStoreError("Windows could not save the key.") from None
    finally:
        ctypes.memset(blob, 0, len(encoded))


def delete(serial: str, provider: str) -> None:
    """Delete this device's saved key. A missing key is already deleted."""
    target = _target(serial, provider)
    try:
        removed = _api.CredDeleteW(target, _CRED_TYPE_GENERIC, 0)
    except (OSError, ctypes.ArgumentError):
        raise CredentialStoreError("Windows could not delete the saved key.") from None
    if not removed and ctypes.get_last_error() != _ERROR_NOT_FOUND:
        raise CredentialStoreError("Windows could not delete the saved key.")
