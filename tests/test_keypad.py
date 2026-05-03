import unittest

from pension.device_profile import Region
from pension.keypad import RandomNumericKeypadMapper
from pension.ocr import OcrResult, OcrWord


class RandomNumericKeypadMapperTests(unittest.TestCase):
    def test_maps_digits_to_nearest_4x3_slots(self):
        result = OcrResult(
            image_path="keypad.png",
            language="kor+eng",
            text="1 2 3\n4 5 6 7\n8 9 0",
            words=[
                OcrWord("1", 118, 51, 25, 64, 96),
                OcrWord("2", 383, 50, 43, 65, 96),
                OcrWord("3", 649, 50, 41, 66, 96),
                OcrWord("4", 110, 204, 46, 64, 96),
                OcrWord("5", 384, 202, 40, 65, 96),
                OcrWord("6", 654, 202, 40, 65, 96),
                OcrWord("7", 922, 204, 44, 64, 96),
                OcrWord("8", 112, 354, 42, 66, 96),
                OcrWord("9", 383, 354, 40, 65, 96),
                OcrWord("0", 924, 354, 41, 66, 96),
            ],
        )
        mapper = RandomNumericKeypadMapper(Region(0, 0, 1080, 465))

        mapping = mapper.map_digits(result)

        self.assertTrue(mapping.complete)
        self.assertEqual(mapping.digits["1"].index, 0)
        self.assertEqual(mapping.digits["7"].index, 7)
        self.assertEqual(mapping.digits["0"].index, 11)
        self.assertEqual([slot.index for slot in mapping.empty_slots], [3, 10])

    def test_translates_mapping_to_screen_coordinates(self):
        result = OcrResult(
            image_path="keypad.png",
            language="kor+eng",
            text="0",
            words=[OcrWord("0", 924, 354, 41, 66, 96)],
        )
        mapper = RandomNumericKeypadMapper(Region(0, 0, 1080, 465))

        mapping = mapper.map_digits(result).translated(0, 1595)

        self.assertEqual(mapping.digits["0"].index, 11)
        self.assertEqual(mapping.digits["0"].x, 945)
        self.assertEqual(mapping.digits["0"].y, 1983)


if __name__ == "__main__":
    unittest.main()
