import XCTest
@testable import WaoCompanionMac

final class CompanionStateTests: XCTestCase {
    func testAggregateUsesBusinessPriority() {
        let activities = [
            activity("RUNNING", unread: false),
            activity("READY", unread: true),
            activity("BLOCKED", unread: true),
            activity("NEEDS_INPUT", unread: true),
        ]
        XCTAssertEqual(CompanionState.aggregate(activities), "NEEDS_INPUT")
        XCTAssertEqual(CompanionState.aggregate(activities.filter { $0.status != "NEEDS_INPUT" }), "BLOCKED")
        XCTAssertEqual(CompanionState.aggregate([]), "IDLE")
    }

    func testFrameMappingMatchesSharedSpriteSheet() {
        XCTAssertEqual(CompanionState.frames(for: "READY"), [10, 1])
        XCTAssertEqual(CompanionState.frames(for: "RUNNING"), [4, 5])
        XCTAssertEqual(CompanionState.frames(for: "NEEDS_INPUT"), [11, 0])
    }

    private func activity(_ status: String, unread: Bool) -> CompanionActivity {
        .init(id: UUID().uuidString, status: status, title: "任务", projectName: "WAO", message: "状态",
              occurredAt: nil, deepLink: "https://wao.example", unread: unread)
    }
}
