import unittest

from pension.device_profile import DeviceProfile
from pension.sitemap import SITEMAP
from pension.routes import (
    InformationContext,
    InformationRoute,
    InformationRouteRegistry,
    route_screen_for_profile_screen,
)


def make_profile():
    return DeviceProfile(
        {
            "device": {"width": 1080, "height": 2340},
            "screens": {
                "home": {
                    "tap_points": {"bottom_menu": {"x": 100, "y": 2160}},
                    "regions": {
                        "filled_results": {"x": 40, "y": 760, "w": 1000, "h": 1200},
                    },
                    "anchors": [],
                },
                "menu": {
                    "tap_points": {
                        "pension_tab": {"x": 625, "y": 520},
                        "balance": {"x": 420, "y": 760},
                        "order": {"x": 460, "y": 840},
                    },
                    "regions": {},
                    "anchors": [],
                },
                "order": {
                    "tap_points": {
                        "account_select": {"x": 550, "y": 545},
                        "account_password": {"x": 900, "y": 548},
                        "buy": {"x": 110, "y": 690},
                        "sell": {"x": 335, "y": 690},
                        "modify_cancel": {"x": 553, "y": 690},
                        "filled": {"x": 758, "y": 690},
                        "balance": {"x": 975, "y": 690},
                    },
                    "regions": {},
                    "anchors": [],
                },
                "account_password_popup": {
                    "tap_points": {
                        "auto_save_toggle": {"x": 920, "y": 1510},
                    },
                    "regions": {
                        "password_dots": {"x": 330, "y": 1220, "w": 420, "h": 90},
                        "auto_save_toggle": {"x": 870, "y": 1465, "w": 140, "h": 95},
                    },
                    "anchors": [],
                },
                "secure_number_keypad": {
                    "tap_points": {
                        "complete": {"x": 810, "y": 2125},
                    },
                    "regions": {
                        "digit_grid": {"x": 0, "y": 1595, "w": 1080, "h": 465},
                    },
                    "anchors": [],
                },
                "balance": {
                    "tap_points": {
                        "account_select": {"x": 540, "y": 370},
                        "realtime": {"x": 105, "y": 505},
                        "profit_loss": {"x": 337, "y": 505},
                        "filled": {"x": 508, "y": 505},
                        "profit_row_detail_expand": {"x": 1015, "y": 675},
                    },
                    "regions": {
                        "cash_asset": {"x": 60, "y": 1615, "w": 960, "h": 165},
                        "holdings_grid": {"x": 0, "y": 1260, "w": 1080, "h": 780},
                    },
                    "anchors": [],
                },
                "balance_account_sheet": {
                    "tap_points": {
                        "irp_account": {"x": 300, "y": 1785},
                        "dc_account": {"x": 300, "y": 1995},
                    },
                    "regions": {},
                    "anchors": [],
                },
                "account_select_sheet": {
                    "tap_points": {
                        "irp_account": {"x": 540, "y": 1170},
                        "dc_account": {"x": 540, "y": 1320},
                    },
                    "regions": {},
                    "anchors": [],
                },
            },
        }
    )


class InformationRouteTests(unittest.TestCase):
    def test_routes_are_information_names_not_account_specific_names(self):
        routes = InformationRouteRegistry().names()

        self.assertEqual(routes, ["매도", "매수", "보유종목", "예수금", "체결결과"])

    def test_sitemap_groups_tabs_under_each_screen(self):
        self.assertEqual(
            set(SITEMAP["잔고"]["tabs"]),
            {"실시간", "매매손익", "체결"},
        )
        self.assertEqual(
            set(SITEMAP["주문"]["tabs"]),
            {"매수", "매도", "정정/취소", "체결", "잔고"},
        )

    def test_sitemap_includes_recognition_map(self):
        self.assertEqual(SITEMAP["잔고"]["profile_screen"], "balance")
        self.assertEqual(SITEMAP["주문"]["profile_screen"], "order")
        self.assertEqual(route_screen_for_profile_screen("balance"), "잔고")
        self.assertEqual(route_screen_for_profile_screen("order"), "주문")
        self.assertIsNone(route_screen_for_profile_screen("search"))
        for screen_data in SITEMAP.values():
            self.assertIn("recognition", screen_data)
            self.assertTrue(screen_data["recognition"]["optional"])

    def test_sitemap_does_not_embed_common_open_steps(self):
        for screen_data in SITEMAP.values():
            self.assertNotIn("open_steps", screen_data)

    def test_sitemap_tabs_include_profile_key_and_selected_anchors(self):
        for screen_name, screen_data in SITEMAP.items():
            for tab_name, tab_route in screen_data["tabs"].items():
                with self.subTest(screen=screen_name, tab=tab_name):
                    self.assertIn("profile_key", tab_route)
                    self.assertIn("selected_anchors", tab_route)
                    self.assertTrue(tab_route["profile_key"])
                    self.assertTrue(tab_route["selected_anchors"])
                    self.assertNotIn("evidence", tab_route)

    def test_route_rejects_tab_that_does_not_belong_to_screen(self):
        with self.assertRaises(ValueError):
            InformationRoute(
                name="잘못된 라우트",
                screen="잔고",
                tab="매수",
                expansions=(),
                read_regions=(),
            )

    def test_cash_route_takes_account_as_parameter(self):
        steps = InformationRouteRegistry().plan("예수금", account="IRP")

        self.assertEqual(
            [step.profile_key for step in steps if step.profile_key],
            [
                "home.bottom_menu",
                "menu.pension_tab",
                "menu.balance",
                "balance.account_select",
                "balance_account_sheet.irp_account",
                "balance.realtime",
                "balance.profit_row_detail_expand",
                "balance.cash_asset",
            ],
        )

    def test_account_parameter_only_changes_account_selection_point(self):
        registry = InformationRouteRegistry()
        irp_keys = [step.profile_key for step in registry.plan("예수금", account="IRP") if step.profile_key]
        dc_keys = [step.profile_key for step in registry.plan("예수금", account="DC") if step.profile_key]

        self.assertEqual(
            [key for key in irp_keys if key != "balance_account_sheet.irp_account"],
            [key for key in dc_keys if key != "balance_account_sheet.dc_account"],
        )
        self.assertIn("balance_account_sheet.irp_account", irp_keys)
        self.assertIn("balance_account_sheet.dc_account", dc_keys)

    def test_buy_and_sell_routes_share_order_path_and_only_change_final_tab(self):
        registry = InformationRouteRegistry()
        buy_keys = [step.profile_key for step in registry.plan("매수", account="IRP") if step.profile_key]
        sell_keys = [step.profile_key for step in registry.plan("매도", account="IRP") if step.profile_key]

        self.assertEqual(
            buy_keys,
            [
                "home.bottom_menu",
                "menu.pension_tab",
                "menu.order",
                "order.account_select",
                "account_select_sheet.irp_account",
                "order.buy",
                "order.account_password",
            ],
        )
        self.assertEqual(buy_keys[:5], sell_keys[:5])
        self.assertEqual(sell_keys[-2], "order.sell")
        self.assertEqual(sell_keys[-1], "order.account_password")

    def test_order_password_gate_is_explicit_event_step(self):
        steps = InformationRouteRegistry().plan("매수", account="IRP")

        self.assertEqual(steps[-2].profile_key, "order.account_password")
        self.assertEqual(steps[-1].action, "이벤트")
        self.assertEqual(steps[-1].event_hooks, ("계좌 비밀번호 입력",))

    def test_order_password_gate_skips_without_ocr_when_account_saved(self):
        context = InformationContext(account="IRP", account_password_saved_accounts=frozenset({"IRP"}))
        steps = InformationRouteRegistry().plan("매수", account="IRP", context=context)

        self.assertNotIn("order.account_password", [step.profile_key for step in steps])
        self.assertEqual(steps[-1].action, "이벤트확인")
        self.assertTrue(steps[-1].skipped)
        self.assertEqual(steps[-1].event_hooks, ("계좌 비밀번호 입력",))

    def test_order_route_context_tracks_buy_and_sell_tabs(self):
        registry = InformationRouteRegistry()

        buy_context = registry.context_after("매수", account="DC", context=InformationContext())
        sell_context = registry.context_after("매도", account="IRP", context=InformationContext())

        self.assertEqual(buy_context.current_screen, "주문")
        self.assertEqual(buy_context.account, "DC")
        self.assertEqual(buy_context.tab, "매수")
        self.assertEqual(sell_context.current_screen, "주문")
        self.assertEqual(sell_context.account, "IRP")
        self.assertEqual(sell_context.tab, "매도")

    def test_filled_result_route_reads_order_filled_tab(self):
        steps = InformationRouteRegistry().plan(
            "체결결과",
            account="IRP",
            context=InformationContext(current_screen="주문", account="IRP", tab="매수"),
        )

        self.assertEqual(
            [step.profile_key for step in steps if step.profile_key],
            ["order.filled", "order.filled_results"],
        )

    def test_plan_skips_already_satisfied_state(self):
        context = InformationContext(
            current_screen="잔고",
            account="IRP",
            tab="실시간",
            expanded=frozenset({"평가손익 상세"}),
        )

        steps = InformationRouteRegistry().plan("예수금", account="IRP", context=context)

        self.assertEqual([step.profile_key for step in steps if step.profile_key], ["balance.cash_asset"])
        self.assertTrue(all(step.skipped for step in steps[:-1]))

    def test_context_can_be_reused_without_reinspection(self):
        context = InformationContext(
            current_screen="잔고",
            account="IRP",
            tab="실시간",
            expanded=frozenset(),
        )
        registry = InformationRouteRegistry()

        steps = registry.plan("보유종목", account="IRP", context=context)

        self.assertEqual([step.profile_key for step in steps if step.profile_key], ["balance.holdings_grid"])

    def test_predicted_context_after_route_execution_tracks_known_state(self):
        registry = InformationRouteRegistry()

        context = registry.context_after("예수금", account="DC", context=InformationContext())

        self.assertEqual(context.current_screen, "잔고")
        self.assertEqual(context.account, "DC")
        self.assertEqual(context.tab, "실시간")
        self.assertEqual(context.expanded, frozenset({"평가손익 상세"}))

    def test_context_round_trip(self):
        context = InformationContext(
            current_screen="잔고",
            account="IRP",
            tab="실시간",
            expanded=frozenset({"평가손익 상세"}),
            account_password_saved_accounts=frozenset({"IRP"}),
        )

        self.assertEqual(InformationContext.from_dict(context.to_dict()), context)

    def test_context_tracks_session_saved_account_password_by_account(self):
        context = InformationContext(account="IRP")

        saved = context.with_password_saved()

        self.assertTrue(saved.is_password_saved("IRP"))
        self.assertFalse(saved.is_password_saved("DC"))

    def test_context_accepts_old_account_password_saved_account_list(self):
        context = InformationContext.from_dict({"account": "IRP", "password_saved_accounts": ["IRP"]})

        self.assertTrue(context.is_password_saved("IRP"))
        self.assertFalse(context.is_password_saved("DC"))

    def test_validate_checks_tap_points_and_read_regions(self):
        missing = InformationRouteRegistry().validate("예수금", make_profile(), account="IRP")

        self.assertEqual(missing, [])

    def test_all_sitemap_tab_profile_keys_are_calibrated(self):
        profile = make_profile()

        missing = []
        for screen_data in SITEMAP.values():
            for tab_route in screen_data["tabs"].values():
                try:
                    profile.tap_point(tab_route["profile_key"])
                except KeyError:
                    missing.append(tab_route["profile_key"])

        self.assertEqual(missing, [])


if __name__ == "__main__":
    unittest.main()
