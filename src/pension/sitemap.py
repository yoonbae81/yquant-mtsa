from __future__ import annotations


SITEMAP = {
    "잔고": {
        "profile_screen": "balance",
        "recognition": {
            "required": (),
            "optional": ("ETF", "잔고", "보유수량", "평가금액", "수익률", "예수금"),
            "forbidden": ("오류", "로그인", "매수", "매도", "정정/취소", "호가"),
            "min_score": 0.3,
        },
        "tabs": {
            "실시간": {
                "step": "실시간 탭 선택",
                "profile_key": "balance.realtime",
                "selected_anchors": ("평가손익", "보유잔고"),
            },
            "매매손익": {
                "step": "매매손익 탭 선택",
                "profile_key": "balance.profit_loss",
                "selected_anchors": ("매매손익",),
            },
            "체결": {
                "step": "체결 탭 선택",
                "profile_key": "balance.filled",
                "selected_anchors": ("체결",),
            },
        },
        "expansions": {
            "평가손익 상세": ("평가손익 상세 펼침", "balance.profit_row_detail_expand"),
        },
    },
    "주문": {
        "profile_screen": "order",
        "recognition": {
            "required": (),
            "optional": ("매수", "매도", "정정/취소", "체결", "잔고", "비밀번호", "주문금액", "호가"),
            "forbidden": ("오류", "로그인"),
            "min_score": 0.3,
        },
        "tabs": {
            "매수": {
                "step": "매수 탭 선택",
                "profile_key": "order.buy",
                "selected_anchors": ("매수", "비밀번호", "주문금액"),
            },
            "매도": {
                "step": "매도 탭 선택",
                "profile_key": "order.sell",
                "selected_anchors": ("매도", "비밀번호"),
            },
            "정정/취소": {
                "step": "정정/취소 탭 선택",
                "profile_key": "order.modify_cancel",
                "selected_anchors": ("정정/취소",),
            },
            "체결": {
                "step": "체결 탭 선택",
                "profile_key": "order.filled",
                "selected_anchors": ("체결",),
            },
            "잔고": {
                "step": "잔고 탭 선택",
                "profile_key": "order.balance",
                "selected_anchors": ("잔고", "보유수량", "평가금액"),
            },
        },
        "expansions": {},
    },
}


def screen_for_profile_screen(profile_screen: str | None) -> str | None:
    for screen, screen_entry in SITEMAP.items():
        if screen_entry["profile_screen"] == profile_screen:
            return screen
    return None


def profile_screen_for_screen(screen: str) -> str:
    try:
        return SITEMAP[screen]["profile_screen"]
    except KeyError as exc:
        raise ValueError(f"Unknown screen: {screen}") from exc
