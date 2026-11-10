"""钢易网 (gtgqw.com) - 杭州螺纹钢价格抓取

从 http://www.gtgqw.com/luowengang/ 列表页筛选杭州相关文章,
取最新的一篇, 解析详情页价格表格.
"""

import re
from dataclasses import dataclass, field
from typing import Optional

import requests
from bs4 import BeautifulSoup
from tabulate import tabulate


BASE_URL = "http://www.gtgqw.com"
LIST_URL = f"{BASE_URL}/luowengang/"
CITY_URL = f"{BASE_URL}/hangzhou/"  # 杭州频道, 内容更全

DEFAULT_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
}


@dataclass
class PriceRow:
    """一行价格数据."""
    name: str          # 品名
    spec: str          # 规格
    material: str      # 材质
    origin: str        # 产地
    price: float       # 价格 (元/吨)
    change: str        # 涨跌


@dataclass
class PriceArticle:
    """一篇价格文章."""
    title: str
    url: str
    publish_time: str = ""
    rows: list[PriceRow] = field(default_factory=list)


class GtgqwClient:
    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update(DEFAULT_HEADERS)

    def _get_html(self, url: str, referer: str = LIST_URL) -> str:
        headers = {"Referer": referer}
        r = self.session.get(url, headers=headers, timeout=15)
        r.raise_for_status()
        return r.content.decode("gb2312", errors="replace")

    def list_articles(self, url: str = LIST_URL, keyword: str = "杭州") -> list[tuple[str, str]]:
        """从列表页抓取标题含关键词的文章, 返回 [(title, url)], 按时间降序."""
        html = self._get_html(url, referer=BASE_URL + "/")
        pattern = re.compile(
            r'href="(http://www\.gtgqw\.com/j/showhz\d+\.html)"[^>]*>([^<]+)</a>'
        )
        all_links = pattern.findall(html)
        # 去重 (按 url) 并保留顺序
        seen = set()
        result = []
        for u, t in all_links:
            if u in seen:
                continue
            seen.add(u)
            if keyword in t:
                result.append((t.strip(), u))
        return result

    def get_latest_hangzhou(self) -> Optional[PriceArticle]:
        """获取最新的杭州螺纹钢价格文章."""
        # 优先从杭州频道页找, 内容更全
        articles = self.list_articles(CITY_URL, keyword="螺纹钢")
        if not articles:
            # 兜底: 从螺纹钢列表页找
            articles = self.list_articles(LIST_URL, keyword="杭州")
        if not articles:
            return None
        title, url = articles[0]
        return self.parse_article(title, url)

    def parse_article(self, title: str, url: str) -> PriceArticle:
        """解析文章详情页, 返回 PriceArticle."""
        html = self._get_html(url, referer=LIST_URL)
        soup = BeautifulSoup(html, "html.parser")

        # 发布时间
        pub_time = ""
        m = re.search(r"\d{4}-\d{2}-\d{2}\s*\d{2}:\d{2}:\d{2}", html)
        if m:
            pub_time = m.group(0)

        # 价格表格
        rows = []
        tables = soup.find_all("table")
        for table in tables:
            trs = table.find_all("tr")
            if len(trs) < 3:
                continue
            # 判断是不是价格表: 表头含"价格"或"元/吨"
            header_cells = [td.get_text(strip=True) for td in trs[1].find_all(["td", "th"])]
            if not any("价格" in c or "品名" in c or "规格" in c for c in header_cells):
                continue
            for tr in trs[2:]:
                cells = [td.get_text(strip=True) for td in tr.find_all(["td", "th"])]
                if len(cells) < 5:
                    continue
                try:
                    price = float(cells[4])
                except (ValueError, IndexError):
                    continue
                rows.append(PriceRow(
                    name=cells[0],
                    spec=cells[1],
                    material=cells[2],
                    origin=cells[3],
                    price=price,
                    change=cells[5] if len(cells) > 5 else "",
                ))
            if rows:
                break

        return PriceArticle(title=title, url=url, publish_time=pub_time, rows=rows)


def main() -> None:
    client = GtgqwClient()

    print("[1/2] 查找杭州最新螺纹钢文章...")
    article = client.get_latest_hangzhou()
    if not article:
        print("  未找到杭州螺纹钢相关文章")
        return

    print(f"  标题: {article.title}")
    print(f"  发布时间: {article.publish_time}")
    print(f"  链接: {article.url}")
    print(f"  价格行数: {len(article.rows)}")

    print()
    print("[2/2] 价格表:")
    table = []
    for i, row in enumerate(article.rows, 1):
        table.append([i, row.name, row.spec, row.material, row.origin, f"{row.price:.0f}", row.change])
    print(tabulate(
        table,
        headers=["#", "品名", "规格", "材质", "产地", "价格(元/吨)", "涨跌"],
        tablefmt="simple",
        stralign="left",
    ))


if __name__ == "__main__":
    main()
