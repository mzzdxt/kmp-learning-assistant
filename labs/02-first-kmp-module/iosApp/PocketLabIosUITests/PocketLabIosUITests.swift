import XCTest

@MainActor
final class PocketLabIosUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testDemoRefreshRendersDatabaseRecordsAndIsIdempotent() throws {
        let app = XCUIApplication()
        app.launchEnvironment["POCKETLAB_DATABASE_NAME"] =
            "pocketlab-ui-\(UUID().uuidString).db"
        app.launch()

        XCTAssertTrue(app.navigationBars["PocketLab"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["把手机留在客厅"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["工作时只留一个浏览器标签页"].exists)
        XCTAssertTrue(app.staticTexts["午饭后散步十二分钟"].exists)
        XCTAssertTrue(
            app.staticTexts["同步完成：更新了 3 条记录"].waitForExistence(timeout: 10)
        )

        app.buttons["刷新"].tap()

        XCTAssertTrue(app.staticTexts["已是最新状态"].waitForExistence(timeout: 10))
    }
}
