import uiautomator2 as u2
from config import ADB_PATH, NEOSMART_PACKAGE, CLICK_TIMEOUT, WAIT_TIMEOUT


class NeoSmartController:
    def __init__(self, adb_path: str | None = None):
        self.adb_path = adb_path or ADB_PATH
        self.d = None

    def connect(self) -> None:
        self.d = u2.connect(self.adb_path)

    def start_app(self) -> bool:
        if not self.d:
            self.connect()
        self.d.app_start(NEOSMART_PACKAGE)
        return self.d(index=0, className="android.webkit.WebView").wait(timeout=WAIT_TIMEOUT)

    def screenshot(self, path: str) -> bytes:
        if not self.d:
            self.connect()
        return self.d.screenshot(path)

    def click_by_text(self, text: str, timeout: int = CLICK_TIMEOUT) -> bool:
        if not self.d:
            self.connect()
        return self.d(text=text, timeout=timeout).click()

    def click_by_resource_id(self, resource_id: str, timeout: int = CLICK_TIMEOUT) -> bool:
        if not self.d:
            self.connect()
        return self.d(resourceId=resource_id, timeout=timeout).click()

    def set_text_by_resource_id(self, resource_id: str, text: str) -> bool:
        if not self.d:
            self.connect()
        return self.d(resourceId=resource_id).set_text(text)

    def get_text_by_resource_id(self, resource_id: str) -> str | None:
        if not self.d:
            self.connect()
        return self.d(resourceId=resource_id).get_text()

    def wait_for_text(self, text: str, timeout: int = WAIT_TIMEOUT) -> bool:
        if not self.d:
            self.connect()
        return self.d(text=text).wait(timeout=timeout)

    def press_back(self) -> None:
        if not self.d:
            self.connect()
        self.d.press("back")

    def press_home(self) -> None:
        if not self.d:
            self.connect()
        self.d.press("home")