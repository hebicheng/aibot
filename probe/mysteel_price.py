import hashlib
import json
import os
import time
import base64
from dataclasses import dataclass, field
from typing import Any

import requests
from tabulate import tabulate
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad


# 密钥从环境变量读取，不写进仓库（该目录脚本只做接口摸底用）
APP_KEY = os.environ.get("MYSTEEL_APP_KEY", "")
APP_SECRET = os.environ.get("MYSTEEL_APP_SECRET", "")
VERSION = "1.0.0"
ENC_SECRET = os.environ.get("MYSTEEL_ENC_SECRET", "")

BASE_URL = "https://price.mysteel.com"
API_PREFIX = "/avgprice/api/jgzx"
LOGIN_URL = "https://passport.mysteel.com/login/loginByPwd"

DEFAULT_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
    "Accept": "application/json, text/plain, */*",
    "Referer": "https://price.mysteel.com/",
    "Origin": "https://price.mysteel.com",
}

# 中文名 -> condition 字段名 的映射
FILTER_FIELD_MAP = {
    "品名": "breedCode",
    "规格": "scCode",
    "材质": "mqCode",
    "网片型号": "scCode1",
    "市场": "marketCode",
    "钢厂/产地": "mrkbrandCode",
    "钢厂": "mrkbrandCode",
    "产地": "mrkbrandCode",
    "交货地": "areaCode",
    "价格属性": "metricCode",
}


@dataclass
class MysteelClient:
    username: str
    password: str
    session: requests.Session = field(default_factory=requests.Session)
    logged_in: bool = False
    # { breed_id: { field_name: { display_name: code } } }
    _filter_cache: dict = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.session.headers.update(DEFAULT_HEADERS)

    # ---- 登录 ----
    def login(self) -> dict:
        pwd_md5 = hashlib.md5(self.password.encode()).hexdigest()
        payload = {
            "username": self.username,
            "password": pwd_md5,
            "vcode": "",
            "rememberStatus": True,
            "jumpPage": "https%3A%2F%2Fprice.mysteel.com%2F",
            "site": "",
            "location": "%E4%BB%B7%E6%A0%BC%E4%B8%AD%E5%BF%83",
        }
        r = self.session.post(
            LOGIN_URL,
            data=json.dumps(payload),
            headers={"Content-Type": "application/json; charset=utf-8"},
            timeout=15,
        )
        r.raise_for_status()
        data = r.json()
        if data.get("code") != 200:
            raise RuntimeError(f"login failed: {data.get('msg')} (code={data.get('code')})")
        jump_to = data["data"]["jumpTo"]
        self.session.get(jump_to, timeout=15, allow_redirects=True)
        self.logged_in = True
        return data["data"]

    # ---- 签名 ----
    def _sign(self, full_path: str):
        ts = str(int(time.time() * 1000))
        raw = f"path{full_path}timestamp{ts}version{VERSION}{APP_SECRET}"
        sign = hashlib.md5(raw.encode()).hexdigest().upper()
        return ts, sign

    def _enc_sign(self, enc_path: str):
        ts = str(int(time.time() * 1000))
        raw = f"path{enc_path}timestamp{ts}version{VERSION}{ENC_SECRET}"
        token = hashlib.md5(raw.encode()).hexdigest().upper()
        return ts, token

    # ---- 解密 ----
    @staticmethod
    def _decrypt_response(resp: dict):
        if not resp.get("isEncrypted"):
            return resp
        key = base64.b64decode(resp["responseSecretKey"])
        iv = base64.b64decode(resp["responseKeyOffset"])
        ct = base64.b64decode(resp["response"])
        cipher = AES.new(key, AES.MODE_CBC, iv)
        pt = unpad(cipher.decrypt(ct), AES.block_size).decode("utf-8")
        return json.loads(pt)

    # ---- 通用请求 ----
    def request(self, path: str, payload: dict, encrypted: bool = True):
        full_path = f"{API_PREFIX}{path}"
        ts, sign = self._sign(full_path)
        url = f"{BASE_URL}{full_path}"

        params_str = ""
        if encrypted:
            enc_path = f"/jgzx{path}"
            ts2, api_token = self._enc_sign(enc_path)
            params_str = f"?Timestamp={ts2}&Api-Token={api_token}"

        headers = {
            "Content-Type": "application/json;charset=UTF-8",
            "version": VERSION,
            "appKey": APP_KEY,
            "timestamp": ts,
            "sign": sign,
        }
        r = self.session.post(
            url + params_str,
            data=json.dumps(payload),
            headers=headers,
            timeout=30,
        )
        r.raise_for_status()
        data = r.json()
        if encrypted:
            data = self._decrypt_response(data)
        return data

    # ---- 筛选条件 ----
    def get_conditions(self, breed_id: str, metric_id: str) -> list:
        """获取原始筛选条件分组列表。"""
        payload = {
            "breedId": breed_id,
            "metricId": metric_id,
            "conditions": {},
            "indexCodeState": 0,
        }
        result = self.request("/condition/conditions", payload, encrypted=False)
        return result.get("data", [])

    def _load_filters(self, breed_id: str, metric_id: str) -> dict:
        """加载并缓存名称->编码映射。返回 {field_name: {name: code}}。"""
        cache_key = f"{breed_id}|{metric_id}"
        if cache_key in self._filter_cache:
            return self._filter_cache[cache_key]

        groups = self.get_conditions(breed_id, metric_id)
        mapping = {}
        for g in groups:
            field = g["value"]
            name_map = {}
            for item in g["conditions"]:
                self._flatten_conditions(item, name_map)
            mapping[field] = name_map
            # 也把中文 showName 作为别名
            mapping[g["showName"]] = name_map

        self._filter_cache[cache_key] = mapping
        return mapping

    @staticmethod
    def _flatten_conditions(node: dict, out: dict) -> None:
        """递归展平嵌套的筛选条件。"""
        name = node.get("name")
        value = node.get("value")
        if name and value and not name.startswith(("ABCD", "EFGH", "IJKL", "MNOP", "QRSTU", "VWXYZ")):
            out[name] = value
        children = node.get("children")
        if not children:
            return
        if isinstance(children, dict):
            for key in ("letter", "region", "normal"):
                for c in children.get(key, []):
                    MysteelClient._flatten_conditions(c, out)
        elif isinstance(children, list):
            for c in children:
                MysteelClient._flatten_conditions(c, out)

    def list_filters(self, breed_id: str, metric_id: str) -> dict:
        """返回所有筛选项的名称->编码映射, key 是字段名(中英都可以查)。"""
        return self._load_filters(breed_id, metric_id)

    def resolve_condition(self, breed_id: str, metric_id: str, filters: dict) -> dict:
        """把中文名的筛选条件转换为接口用的 condition 字典.

        filters 可以用中文字段名, 例如:
            {
                "品名": ["螺纹钢", "盘螺"],
                "规格": ["Φ18"],
                "市场": ["上海", "北京"],
            }
        """
        mapping = self._load_filters(breed_id, metric_id)
        result = {}
        for key, names in filters.items():
            # 先找字段名
            field = FILTER_FIELD_MAP.get(key, key)
            if field not in mapping:
                raise ValueError(f"未知的筛选字段: {key} (可用字段: {', '.join(FILTER_FIELD_MAP.keys())})")
            name_map = mapping[field]
            codes = []
            if isinstance(names, str):
                names = [names]
            for name in names:
                if name not in name_map:
                    raise ValueError(f"在 {key} 中找不到选项 '{name}', 可选: {', '.join(list(name_map.keys())[:20])}...")
                codes.append(name_map[name])
            result[field] = codes
        return result

    # ---- 搜索 ----
    def search_price(
        self,
        breed_id: str,
        metric_id: str,
        filters: dict | None = None,
        date_query_type: str = "custom",
        date_string: list | None = None,
        avg_price_enum: int = 1,
        page_number: int = 1,
        page_size: int = 20,
        date_type: int = 1,
    ) -> dict:
        """搜索价格. filters 可以用中文名 (会自动转编码)."""
        condition = {}
        if filters:
            condition = self.resolve_condition(breed_id, metric_id, filters)

        payload = {
            "authBreedId": breed_id,
            "metricId": metric_id,
            "indexCodeState": 0,
            "avgPriceParamVo": {
                "avgPriceEnum": avg_price_enum,
                "dateType": date_type,
                "dateString": date_string or [],
                "dateQueryType": date_query_type,
                "pageNumber": page_number,
                "pageSize": page_size,
                "condition": condition,
            },
        }
        result = self.request("/avgPrice/calculate", payload, encrypted=True)
        if result.get("code") != 200:
            raise RuntimeError(f"search failed: {result.get('msg')} (code={result.get('code')})")
        return result.get("data", {})


def main() -> None:
    username = os.environ.get("MYSTEEL_USERNAME", "")
    password = os.environ.get("MYSTEEL_PASSWORD", "")
    if not username or not password:
        raise SystemExit("请先设置环境变量 MYSTEEL_USERNAME / MYSTEEL_PASSWORD")
    client = MysteelClient(username=username, password=password)

    print("[1/4] login...")
    user_info = client.login()
    print(f"  ok: {user_info['memberName']} (userId={user_info['userId']})")

    print()
    print("[2/4] load filter mappings (breedId=1-1, metricId=291)...")
    flt = client.list_filters("1-1", "291")
    # 按字段名打印总数
    fields = ["breedCode", "scCode", "mqCode", "scCode1", "marketCode", "mrkbrandCode", "areaCode", "metricCode"]
    name_fields = ["品名", "规格", "材质", "网片型号", "市场", "钢厂/产地", "交货地", "价格属性"]
    for f, nf in zip(fields, name_fields):
        if f in flt:
            print(f"  {nf} ({f}): {len(flt[f])} 项  (例: {', '.join(list(flt[f].keys())[:5])})")

    print()
    print("[3/4] resolve condition by name...")
    cond = client.resolve_condition("1-1", "291", {
        "品名": "螺纹钢",
        "规格": "Φ18",
        "材质": "HRB400",
        "钢厂": "萍钢",
    })
    print(f"  {cond}")

    print()
    print("[4/4] search price by name...")
    data = client.search_price(
        breed_id="1-1",
        metric_id="291",
        filters={
            "品名": "螺纹钢",
            "市场": "上海",
            "钢厂": "萍钢",
        },
        date_query_type="custom",
        date_string=["2026-08-21", "2026-08-21"],
    )

    rows = data.get("groupAvgPrices", [])
    total = data.get("page", {}).get("total", 0)
    print(f"  total: {total}, this page: {len(rows)}\n")

    table = []
    headers = ["#", "品种组合", "价格", "单位", "市场", "钢厂", "涨跌", "指数代码", "日期"]
    for i, row in enumerate(rows, 1):
        avg = row["avgPrice"]
        vals = avg.get("values", {})
        table.append([
            i,
            row.get("groupName", "")[:40],
            avg.get("price", ""),
            row.get("unitName", ""),
            vals.get("marketCode", ""),
            vals.get("mrkbrandCode", ""),
            avg.get("volatility", "") if avg.get("volatility") is not None else "-",
            row.get("indexCode", ""),
            avg.get("dateString", ""),
        ])
    print(tabulate(table, headers=headers, tablefmt="simple", stralign="left"))


if __name__ == "__main__":
    main()
