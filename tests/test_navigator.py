import unittest

from pension.navigator import NavigationContext, NavigationTarget, Navigator


class NavigatorTests(unittest.TestCase):
    def test_plan_uses_common_menu_entry_for_screen_open(self):
        steps = Navigator().plan(NavigationTarget(screen="주문", tab="매수"))

        self.assertEqual(
            [step.profile_key for step in steps if step.profile_key],
            ["home.bottom_menu", "menu.pension_tab", "menu.order", "order.buy"],
        )

    def test_plan_skips_bottom_menu_when_menu_is_current_screen(self):
        steps = Navigator().plan(
            NavigationTarget(screen="잔고", tab="실시간"),
            context=NavigationContext(current_screen="메뉴"),
        )

        self.assertEqual(
            [step.profile_key for step in steps if step.profile_key],
            ["menu.pension_tab", "menu.balance", "balance.realtime"],
        )

    def test_plan_reuses_satisfied_target_state(self):
        steps = Navigator().plan(
            NavigationTarget(screen="잔고", account="IRP", tab="실시간", expansions=("평가손익 상세",)),
            context=NavigationContext(
                current_screen="잔고",
                account="IRP",
                tab="실시간",
                expanded=frozenset({"평가손익 상세"}),
            ),
        )

        self.assertEqual([step.profile_key for step in steps if step.profile_key], [])
        self.assertTrue(all(step.skipped for step in steps))

    def test_tab_state_is_not_reused_across_screens(self):
        steps = Navigator().plan(
            NavigationTarget(screen="잔고", tab="실시간"),
            context=NavigationContext(current_screen="주문", tab="실시간"),
        )

        self.assertIn("balance.realtime", [step.profile_key for step in steps if step.profile_key])


if __name__ == "__main__":
    unittest.main()
