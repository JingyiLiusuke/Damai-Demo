
from appium import webdriver
from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException, NoSuchElementException
from appium.options.android import UiAutomator2Options
import time
import datetime
import random

# ================= 配置区域 =================
# 请使用 'adb devices' 查看设备名称
DEVICE_NAME = 'YOUR_DEVICE_ID'
# 大麦 Android 包名和启动 Activity (通常是主页，但建议手动进入详情页后启动脚本)
APP_PACKAGE = 'cn.damai'
APP_ACTIVITY = 'cn.damai.homepage.MainActivity'

# Appium Server 地址
COMMAND_EXECUTOR = 'http://127.0.0.1:4723/wd/hub'

# 抢票参数配置
Ticket_Tier_Index = 0  # 票档位次：0代表第一个票档，1代表第二个...
Viewer_Count = 1  # 需要购买的数量/观演人数
# [新增] 目标抢票时间，格式：YYYY-MM-DD HH:MM:SS.ms (毫秒级)
# 请务必修改为你实际的开抢时间，例如：'2025-12-01 20:00:00.000'
TARGET_TIME_STR = '2025-12-05 13:59:59.700'

# [新增] “立即购买”按钮的中心坐标 (基于用户提供的 [473,2582][1309,2632] 计算)
PURCHASE_BUTTON_COORDS = (891, 2607)


class DamaiBot:
    def __init__(self):
        capabilities = {
            'platformName': 'Android',
            'platformVersion': '12',
            'automationName': 'UiAutomator2',
            'deviceName': DEVICE_NAME,
            'appPackage': APP_PACKAGE,
            'appActivity': APP_ACTIVITY,
            'noReset': True,  # 重要：保留登录状态，不清除数据
            'newCommandTimeout': 6000,

            # 🌟 延迟优化配置 🌟
            'ignoreUnimportantViews': True,  # 优化页面源码/元素查找速度
            'mjpegServerScreenshotQuality': 20,  # 优化截图传输速度
            'mjpegServerPort': 8100,  # 启用 MJPEG Server

            # 进一步优化：通过 settings 字典设置
            'appium:settings': {
                'waitForIdleTimeout': 50,  # 降低等待设备空闲的超时时间（单位：毫秒）
                'waitForSelectorTimeout': 500  # 降低查找元素的超时时间（可选，默认10秒）
            }
        }
        self.driver = None
        # 适配 v3+ 版本：将字典转换为 Options 对象
        self.options = UiAutomator2Options()
        self.options.load_capabilities(capabilities)

        self.driver = None

    def start(self):
        print(">>> 正在连接手机...")
        # 适配 v3+ 版本：使用 options 参数替代 desired_capabilities
        self.driver = webdriver.Remote(COMMAND_EXECUTOR, options=self.options)
        measure_latency(self.driver, "Ping Check")
        measure_screenshot_latency(self.driver)
        self.wait = WebDriverWait(self.driver, 5)  # 设置显式等待最长5秒
        print(">>> 启动成功，请手动导航至演唱会详情页，脚本将在3秒后开始抢票逻辑...")
        time.sleep(3)
        self.buy_process()


    def find_and_click(self, by, value, timeout=2, strict=True):
        """
        封装查找并点击的方法，包含显式等待
        """
        try:
            element = WebDriverWait(self.driver, timeout).until(
                EC.element_to_be_clickable((by, value))
            )
            element.click()
            return True
        except TimeoutException:
            if strict:
                print(f"!!! 未找到元素或不可点击: {value}")
            return False

    def wait_for_target_time(self):
        """
        根据用户输入时间定点响应，直到接近抢票时间才开始轮询点击。
        """
        try:
            # 解析目标时间字符串
            target_time = datetime.datetime.strptime(TARGET_TIME_STR, '%Y-%m-%d %H:%M:%S.%f')
        except ValueError:
            print(f"!!! 目标时间格式错误: {TARGET_TIME_STR}。请使用 YYYY-MM-DD HH:MM:SS.ms 格式。")
            return

        print(f">>> 目标抢票时间设定为: {target_time.strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}")

        # 高速等待循环
        while datetime.datetime.now() < target_time:
            time_until = target_time - datetime.datetime.now()

            # 当剩余时间大于1秒时，使用较大的休眠时间减少CPU占用
            if time_until.total_seconds() > 1:
                print(f"等待中... 剩余 {time_until.total_seconds():.3f} 秒", end='\r')
                time.sleep(0.5)
            # 当剩余时间小于1秒时，进入毫秒级精度等待 (提高精度)
            elif time_until.total_seconds() > 0:
                print(f"等待中... 剩余 {time_until.total_seconds():.3f} 秒 - 进入高速等待", end='\r')
                time.sleep(0.001)
            else:
                break

        # 退出等待循环，开始抢票
        #print("\n>>> 时间到达！开始高频点击‘立即购买’按钮...")

    def buy_process(self):
        """
        核心抢票流程
        """
        print(">>> 开始监控‘立即购买’按钮...")
        self.wait_for_target_time()

        # 1. 尝试点击详情页底部的 [立即购买] 或 [立即预定]
        # [优化] 使用坐标点击 (Tap by Coordinates) 替代 find_and_click，速度最快
        start_time = time.time()

        # 循环点击，直到选座弹窗出现
        while time.time() - start_time < 50:  # 快速点击5秒
            try:
                # 使用 tap() 方法，直接在指定坐标点击，跳过元素查找环节
                # duration=50ms 为按压时间，模仿人手点击
                self.driver.tap([PURCHASE_BUTTON_COORDS], 20)

                # 检查选座页面的“确定”按钮是否出现 (作为页面跳转成功的标志)
                # timeout 设为 0.1 秒快速检查，不阻塞循环
                if self.find_and_click(AppiumBy.ID,"cn.damai:id/btn_buy_view",timeout=0.01,strict=False):
                    print(">>> 坐标点击购买按钮成功...")
                    break
                print(time.time() - start_time)
                if self.find_and_click(AppiumBy.XPATH,
                    "/hierarchy/android.widget.FrameLayout/android.widget.LinearLayout/android.widget.FrameLayout/android.widget.LinearLayout/android.widget.FrameLayout/android.widget.FrameLayout/android.widget.FrameLayout/android.widget.RelativeLayout/android.widget.LinearLayout[3]/android.widget.FrameLayout/android.widget.TextView[2]",
                    timeout=0.01,
                    strict=False):
                    break

            except Exception:
                # 忽略点击失败异常，继续循环
                pass
            # 极短的延迟，防止 CPU 满载，并提高点击频率 (约 100次/秒)
            time.sleep(0.001)
        else:
            print("!!! 警告：5秒内未能成功点击并跳转到选座页，可能已错过抢票时间或定位错误。")
            return  # 抢票失败，退出
        """
        while True:
            # 1. 尝试点击详情页底部的 [立即购买] 或 [立即预定]
            # 这里使用了 XPath 模糊匹配文本，适应不同状态
            btn_found = self.find_and_click(
                AppiumBy.ID,
                "cn.damai:id/trade_project_detail_purchase_status_bar_container_fl",
                timeout=0.5,
                strict=False
            )

            if btn_found:
                print(">>> 点击购买按钮成功！进入选座页...")
                break
            else:
                # 某些时候页面需要刷新，或者按钮是灰色（倒计时中），这里可以添加刷新逻辑
                pass
        
        # 2. 选择票档 (进入弹窗页面)
        try:
            print(">>> 正在选择票档...")
            # 注意：大麦的票档通常是一个 RecyclerView 或 FlowLayout
            # 这里演示简单的逻辑：等待“确定”按钮出现，说明页面加载完成
            # 实际场景中，你可能需要根据 Ticket_Tier_Index 去点击具体的票档

            # 示例：点击显示的第一个票档 (需用 Appium Inspector 确认具体的 resource-id)
            # 假设票档的 resource-id 为 cn.damai:id/item_text
            # tiers = self.driver.find_elements(AppiumBy.ID, "cn.damai:id/item_text")
            # if len(tiers) > Ticket_Tier_Index:
            #     tiers[Ticket_Tier_Index].click()

            # 点击数量加号（如果需要多张）
            #if Viewer_Count > 1:
            #   print(f">>> 增加票数至 {Viewer_Count} 张...")
            #   for _ in range(Viewer_Count - 1):
            #       self.find_and_click(AppiumBy.ID, "cn.damai:id/btn_plus", timeout=1)

            # 3. 点击选座页面的 [确定] 按钮
            print(">>> 尝试点击选座页‘确定’...")
            #self.find_and_click(AppiumBy.XPATH, "//*[@text='确定']", timeout=2)
            while True:
                # 1. 尝试点击详情页底部的 [立即购买] 或 [立即预定]
                # 这里使用了 XPath 模糊匹配文本，适应不同状态 cn.damai:id/btn_buy_view
                btn_found = self.find_and_click(
                    AppiumBy.ID,
                    "cn.damai:id/btn_buy_view",
                    timeout=0.5,
                    strict=False
                )

                if btn_found:
                    print(">>> 点击选座成功...")
                    break

        except Exception as e:
            print(f"!!! 选座环节出错: {e}")
    """
        # 4. 订单确认页 (提交订单)
        try:
            print(">>> 进入订单确认页，选择观演人...")

            # 这一步通常需要勾选观演人。如果之前没选过，需要点击 checkbox
            # 建议提前在 App 设置好默认观演人，脚本直接点提交最快

            # 狂点 [提交订单]
            start_time = time.time()
            while time.time() - start_time < 100:  # 尝试点击10秒
                # 查找提交按钮，通常 ID 为 cn.damai:id/tv_submit 或文本为 提交订单 继续尝试cn.damai:id/damai_theme_dialog_confirm_btn
                clicked = self.find_and_click(
                    AppiumBy.XPATH,
                    "/hierarchy/android.widget.FrameLayout/android.widget.LinearLayout/android.widget.FrameLayout/android.widget.LinearLayout/android.widget.FrameLayout/android.widget.FrameLayout/android.widget.FrameLayout/android.widget.RelativeLayout/android.widget.LinearLayout[3]/android.widget.FrameLayout/android.widget.TextView[2]",
                    timeout=0.01,
                    strict=False
                )
                print(time.time() - start_time)
                time.sleep(random.uniform(0, 0.5))
                self.find_and_click(AppiumBy.ID,"cn.damai:id/damai_theme_dialog_confirm_btn",timeout=0.01,strict=False)
                """    
                if clicked:
                    print(">>> 提交请求已发送！请检查是否跳转支付...")
                    break
                """

        except Exception as e:
            print(f"!!! 提交订单出错: {e}")

    def stop(self):
        if self.driver:
            self.driver.quit()


def measure_latency(driver, command_name="Generic"):
    start_time = time.time() * 1000  # 转换为毫秒

    # 执行一个极轻量级的命令，例如获取当前 Activity 或 Context
    # 避免使用 page_source 或 screenshot 来测算纯延迟
    _ = driver.current_context

    end_time = time.time() * 1000
    duration = end_time - start_time
    print(f"[{command_name}] Latency: {duration:.2f} ms")
    return duration


# 测试截图的具体耗时
def measure_screenshot_latency(driver):
    start_time = time.time() * 1000
    _ = driver.get_screenshot_as_base64()
    end_time = time.time() * 1000
    print(f"[Screenshot] Time: {end_time - start_time:.2f} ms")

if __name__ == "__main__":
    bot = DamaiBot()
    try:
        bot.start()
    except KeyboardInterrupt:
        print("\n>>> 用户停止脚本")
    except Exception as e:
        print(f"\n!!! 发生未知错误: {e}")
    finally:
        # bot.stop() # 调试时可以注释掉，方便看手机当前停在哪个页面
        pass