"""验证专用权限夹具的真实 Excel 内容；不连接或修改数据库。"""
import json
import sys
import zipfile
import xml.etree.ElementTree as ET

namespace = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
with zipfile.ZipFile(sys.argv[1]) as workbook:
    texts = [
        node.text or ""
        for path in workbook.namelist()
        if path.startswith("xl/") and path.endswith(".xml")
        for node in ET.fromstring(workbook.read(path)).findall(".//s:t", namespace)
    ]
assert "下级B" in texts, "缺少允许导出的记录"
assert not any(name in texts for name in ["本部A", "本部B", "下级A", "外部A", "外部B"]), "导出越权记录"
assert not any("内部备注" in text or "仅管理员可见" in text for text in texts), "导出隐藏字段"
print(json.dumps({"passed": True, "records": ["下级B"], "hiddenFieldAbsent": True}, ensure_ascii=False))
