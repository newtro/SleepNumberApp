import XCTest
@testable import BedKit

final class ModelTests: XCTestCase {
    func testSideMappingDefaultsAndSwap() {
        let normal = SideMapping()
        XCTAssertEqual(normal.mcrSide(for: .left), .zero)
        XCTAssertEqual(normal.mcrSide(for: .right), .one)
        XCTAssertEqual(normal.side(forMCR: .zero), .left)
        XCTAssertEqual(normal.fuzionSide(for: .left), .left)

        let swapped = SideMapping(swapped: true)
        XCTAssertEqual(swapped.mcrSide(for: .left), .one)
        XCTAssertEqual(swapped.mcrSide(for: .right), .zero)
        XCTAssertEqual(swapped.side(forMCR: .zero), .right)
        XCTAssertEqual(swapped.fuzionSide(for: .left), .right)
    }

    func testMotionPlannerIsBoundedAndIncremental() {
        let planner = MotionPlanner(step: 10, retargetMargin: 3)
        XCTAssertEqual(planner.nextTarget(from: 40, direction: .up), 50)
        XCTAssertEqual(planner.nextTarget(from: 95, direction: .up), 100)
        XCTAssertNil(planner.nextTarget(from: 100, direction: .up))
        XCTAssertEqual(planner.nextTarget(from: 40, direction: .down), 30)
        XCTAssertEqual(planner.nextTarget(from: 4, direction: .down), 0)
        XCTAssertNil(planner.nextTarget(from: 0, direction: .down))
        // Never more than one step away from the measured position, for every start point.
        for start in 0...100 {
            for direction in [MoveDirection.up, .down] {
                if let target = planner.nextTarget(from: start, direction: direction) {
                    XCTAssertLessThanOrEqual(abs(target - start), 10)
                    XCTAssertTrue((0...100).contains(target))
                }
            }
        }
        XCTAssertFalse(planner.shouldRetarget(current: 45, target: 50, direction: .up))
        XCTAssertTrue(planner.shouldRetarget(current: 47, target: 50, direction: .up))
        XCTAssertTrue(planner.shouldRetarget(current: 33, target: 30, direction: .down))
        XCTAssertFalse(planner.shouldRetarget(current: 34, target: 30, direction: .down))
    }

    func testProtocolDetectionFromServices() {
        XCTAssertEqual(BedProtocol.detect(serviceUUIDs: ["ffffd1fd-388d-938b-344a-939d1f6efee0"]), .mcr)
        XCTAssertEqual(BedProtocol.detect(serviceUUIDs: ["180A", "09D23FAE-90E6-44C2-95B6-0B3D0F1ABF25"]), .fuzion)
        XCTAssertNil(BedProtocol.detect(serviceUUIDs: ["180A", "1800"]))
    }

    func testDetectorMatchesOwnersMACName() {
        let result = BedDetector.classify(AdvertisementInfo(name: "64:db:a0:9a:32:d3"))
        XCTAssertTrue(result.isCandidate)
        XCTAssertEqual(result.reasons, [.selectComfortMAC])
        XCTAssertEqual(result.likelyProtocol, .mcr)
        XCTAssertEqual(BedDetector.mcrAddress(fromMACName: "64:db:a0:9a:32:d3"), 0x32D3)
    }

    func testDetectorOtherSignals() {
        XCTAssertEqual(BedDetector.classify(AdvertisementInfo(name: "Smart bed 1A2B")).reasons, [.smartBedName])
        XCTAssertEqual(BedDetector.classify(AdvertisementInfo(name: "AA:BB:CC:DD:EE:FF")).reasons, [.macName])
        XCTAssertEqual(BedDetector.classify(AdvertisementInfo(name: nil, serviceUUIDs: [BedProtocol.mcrServiceUUID])).likelyProtocol, .mcr)
        XCTAssertEqual(BedDetector.classify(AdvertisementInfo(name: nil, serviceUUIDs: [BedProtocol.fuzionServiceUUID])).likelyProtocol, .fuzion)
        XCTAssertEqual(BedDetector.classify(AdvertisementInfo(name: nil, manufacturerData: Data([0x4E, 0x53, 0x01]))).reasons, [.manufacturerData])
        XCTAssertFalse(BedDetector.classify(AdvertisementInfo(name: "JBL Flip 5")).isCandidate)
        XCTAssertFalse(BedDetector.classify(AdvertisementInfo(name: "64:db:a0:9a:32")).isCandidate)
        XCTAssertFalse(BedDetector.isMACLike("64:db:a0:9a:32:zz"))
        XCTAssertGreaterThan(BedDetector.classify(AdvertisementInfo(name: "64:DB:A0:9A:32:D3")).score,
                             BedDetector.classify(AdvertisementInfo(name: "AA:BB:CC:DD:EE:FF")).score)
    }

    func testHexHelpers() {
        XCTAssertEqual(Data(hex: "16 16 0a")?.hexString, "16 16 0a")
        XCTAssertEqual(Data(hex: "16160A"), Data([0x16, 0x16, 0x0A]))
        XCTAssertNil(Data(hex: "1"))
        XCTAssertNil(Data(hex: "zz"))
    }
}
