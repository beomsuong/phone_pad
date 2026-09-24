# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller build definition for the Phone Pad PC server.

Single windowed exe: `pc_server/dist/PhonePadServer.exe`.

Build it with `build_exe.ps1` - that script creates a throwaway venv OUTSIDE the
repository and never touches the global Python installation (the pytest baseline
for this repo assumes pystray/Pillow are NOT installed globally).

Manual equivalent (inside such a venv, from pc_server/):
    python -m PyInstaller --noconfirm --clean phone_pad_server.spec

Notes:
- `console=False` is the `--noconsole` part. In that mode `sys.stdout`/`sys.stderr`
  are None and `print()` becomes a silent no-op, so `logging_setup.configure_stdio()`
  redirects them to %LOCALAPPDATA%\\PhonePad\\server.log at startup.
- `pystray` picks its backend at import time via `importlib`, so PyInstaller's static
  analysis does not see `pystray._win32`; it has to be a hidden import.
"""
import os

HERE = os.path.abspath(os.path.dirname(SPEC))  # noqa: F821 - SPEC is injected by PyInstaller

a = Analysis(  # noqa: F821
    ["server.py"],
    pathex=[HERE],
    binaries=[],
    datas=[],
    hiddenimports=[
        # pystray resolves its Windows backend dynamically (pystray/__init__.py).
        "pystray._win32",
        # Only these two Pillow modules are used (tray.py builds the icon in code).
        "PIL.Image",
        "PIL.ImageDraw",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        # NOTE: tkinter used to be excluded here ("the server never shows a GUI of
        # its own", worth ~5MB). It is REQUIRED now - the server window (gui.py) is
        # the default mode, and without tkinter the exe silently falls back to the
        # tray-only mode. Keep the Pillow/Tk bridge out, though: tray.py builds its
        # icon with PIL.Image/ImageDraw and never needs ImageTk.
        "PIL.ImageTk",
        "PIL.ImageQt",
        "PIL.ImageShow",
        # Nothing here uses the scientific stack or a Qt binding.
        "numpy",
        "scipy",
        "pandas",
        "matplotlib",
        "PyQt5",
        "PyQt6",
        "PySide2",
        "PySide6",
        # Dev-only tooling.
        "pytest",
        "_pytest",
        "IPython",
        "pydoc_data",
    ],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)  # noqa: F821

exe = EXE(  # noqa: F821
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="PhonePadServer",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,          # --noconsole / windowed
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
