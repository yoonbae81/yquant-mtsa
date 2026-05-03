import unittest

from pension.ocr import OcrResult
from pension.device_profile import DeviceProfile
from pension.screen_state import ScreenStateChecker


def make_profile():
    return DeviceProfile(
        {
            "device": {"width": 1080, "height": 2340},
            "screens": {
                "balance": {
                    "anchors": {
                        "required": ["퇴직연금"],
                        "optional": ["ETF", "리츠", "잔고", "보유수량", "평가금액", "수익률"],
                        "forbidden": ["오류", "로그인"],
                        "min_score": 0.6,
                    },
                    "tap_points": {},
                    "regions": {},
                },
                "order": {
                    "anchors": {
                        "required": ["매수", "비밀번호"],
                        "optional": ["매도", "현재가", "주문금액"],
                        "forbidden": ["오류", "로그인"],
                        "min_score": 0.7,
                    },
                    "tap_points": {},
                    "regions": {},
                },
            },
        }
    )


class ScreenStateTests(unittest.TestCase):
    def test_anchor_check_normalizes_whitespace(self):
        result = OcrResult(
            image_path="screen.png",
            language="kor+eng",
            text="계좌 비밀번호 입력\n매 수",
            words=[],
        )

        check = ScreenStateChecker().check_anchors(
            "account_password_popup",
            ["계좌 비밀번호 입력", "매수"],
            result,
        )

        self.assertTrue(check.passed)
        self.assertEqual(check.matched, ["계좌 비밀번호 입력", "매수"])

    def test_anchor_check_supports_optional_and_forbidden_rules(self):
        result = OcrResult(
            image_path="screen.png",
            language="kor+eng",
            text="퇴직연금 ETF 매수 주문가능",
            words=[],
        )

        check = ScreenStateChecker().check_anchors(
            "order",
            {
                "required": ["퇴직연금", "ETF"],
                "optional": ["매수", "매도", "주문가능"],
                "forbidden": ["오류"],
                "min_score": 0.8,
            },
            result,
        )

        self.assertTrue(check.passed)
        self.assertEqual(check.optional_matched, ["매수", "주문가능"])
        self.assertEqual(check.forbidden_matched, [])

    def test_inspect_detects_screen_account_tab_and_expanded_state(self):
        result = OcrResult(
            image_path="screen.png",
            language="kor+eng",
            text="퇴직연금 ETF 리츠 잔고 개인형IRP 실시간 매매손익 체결 평가손익 보유잔고 예수금",
            words=[],
        )

        snapshot = ScreenStateChecker().inspect(make_profile(), result)

        self.assertEqual(snapshot.screen_key, "balance")
        self.assertEqual(snapshot.current_screen, "잔고")
        self.assertEqual(snapshot.account, "IRP")
        self.assertEqual(snapshot.tab, "실시간")
        self.assertEqual(snapshot.expanded, frozenset({"평가손익 상세"}))

    def test_inspect_accepts_high_score_screen_candidate_when_required_anchor_is_missed(self):
        result = OcrResult(
            image_path="screen.png",
            language="kor+eng",
            text="ETF 잔고 보유수량 평가금액 수익률 개인형IRP 실시간",
            words=[],
        )

        snapshot = ScreenStateChecker().inspect(make_profile(), result)

        self.assertEqual(snapshot.screen_key, "balance")
        self.assertEqual(snapshot.current_screen, "잔고")

    def test_inspect_uses_route_map_when_profile_anchor_score_is_low(self):
        result = OcrResult(
            image_path="screen.png",
            language="kor+eng",
            text="개인형IRP 비밀번호 매수 주문금액 원",
            words=[],
        )

        snapshot = ScreenStateChecker().inspect(make_profile(), result)

        self.assertEqual(snapshot.screen_key, "order")
        self.assertEqual(snapshot.current_screen, "주문")
        self.assertEqual(snapshot.tab, "매수")

    def test_inspect_does_not_treat_order_balance_tab_as_balance_screen(self):
        result = OcrResult(
            image_path="screen.png",
            language="kor+eng",
            text="개인형IRP 매수 매도 정정/취소 체결 잔고 현재가 호가 보유수량 평가금액",
            words=[],
        )

        snapshot = ScreenStateChecker().inspect(make_profile(), result)

        self.assertEqual(snapshot.screen_key, "order")
        self.assertEqual(snapshot.current_screen, "주문")
        self.assertEqual(snapshot.tab, "잔고")


if __name__ == "__main__":
    unittest.main()
