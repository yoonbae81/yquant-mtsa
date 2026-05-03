import unittest

from pension.holdings_grid import BalanceHoldingsGridParser
from pension.ocr import OcrResult, OcrWord


def word(text, left, top):
    return OcrWord(text=text, left=left, top=top, width=80, height=30, confidence=90)


class HoldingsGridTests(unittest.TestCase):
    def test_parses_rows_from_paired_scroll_captures(self):
        left = OcrResult(
            image_path="left.png",
            language="kor+eng",
            text="",
            words=[
                word("초기화", 30, 1240),
                word("san", 30, 1400),
                word("RISE", 30, 1300),
                word("삼성전자SK하이닉스", 90, 1300),
                word("채권혼합50", 30, 1333),
                word("100", 780, 1300),
                word("KODEX", 30, 1460),
                word("국고채", 30, 1505),
                word("10년액티브", 30, 1550),
                word("40", 800, 1460),
            ],
        )
        right = OcrResult(
            image_path="right.png",
            language="kor+eng",
            text="",
            words=[
                word("RISE", 30, 1300),
                word("삼성전자SK하이닉스", 30, 1345),
                word("10,005", 780, 1300),
                word("KODEX", 30, 1460),
                word("국고채", 30, 1505),
                word("111,430", 760, 1460),
            ],
        )

        rows = BalanceHoldingsGridParser().parse(left, right)

        self.assertEqual(rows[0].name, "RISE삼성전자SK하이닉스채권혼합50")
        self.assertEqual(rows[0].quantity, 100)
        self.assertEqual(rows[0].average_price, 10005)
        self.assertEqual(rows[1].name, "KODEX국고채10년액티브")
        self.assertEqual(rows[1].quantity, 40)
        self.assertEqual(rows[1].average_price, 111430)

    def test_normalizes_common_balance_ocr_name_errors(self):
        left = OcrResult(
            image_path="left.png",
            language="kor+eng",
            text="",
            words=[
                word("RISEKOFR2", 30, 1300),
                word("리액티브(합성)", 30, 1345),
                word("1,700", 780, 1300),
                word("KODEX", 30, 1460),
                word("144", 120, 1460),
                word("10년액티브", 30, 1505),
                word("133", 800, 1460),
            ],
        )
        right = OcrResult(
            image_path="right.png",
            language="kor+eng",
            text="",
            words=[
                word("105,450", 780, 1300),
                word("105,570", 760, 1460),
            ],
        )

        rows = BalanceHoldingsGridParser().parse(left, right)

        self.assertEqual(rows[0].name, "RISEKOFR금리액티브(합성)")
        self.assertEqual(rows[1].name, "KODEX국고채10년액티브")


if __name__ == "__main__":
    unittest.main()
