"""图标门禁（tools/ci/check.sh lint 运行，单跑：python3 tools/guards/check_icons.py）。

图标只来自 tools/icons/material_symbols.py 的清单与固定快照（Material Symbols Rounded）。手写或从别处拷来的矢量图
会把样式与版本混进来（圆角与直角、新旧几何混在同一组图标里）。核对逻辑在 material_symbols.check，这里只接进门禁。
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "icons"))

from material_symbols import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
