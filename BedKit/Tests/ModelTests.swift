import XCTest
@testable import BedKit

final class ModelTests: XCTestCase {
    func testSideMappingFollowsTheApp() {
        // Official app: right = 0, left = 1.
        let normal = SideMapping()
        XCTAssertEqual(normal.mcrSide(for: .right), .zero)
        XCTAssertEqual(normal.mcrSide(for: .left), .one)
        XCTAssertEqual(normal.side(forMCR: .zero), .right)
        XCTAssertEqual(normal.fuzionSide(for: .left), .left)

        let swapped = SideMapping(swapped: true)
        XCTAssertEqual(swapped.mcrSide(for: .left), .zero)
        XCTAssertEqual(swapped.mcrSide(for: .right), .one)
        XCTAssertEqual(swapped.side(forMCR: .zero), .left)
        XCTAssertEqual(swapped.fuzionSide(for: .left), .right)
    }

    func testHoldTargetsMatchTheApp() {
        XCTAssertEqual(MoveDirection.up.holdTarget, 100)
        XCTAssertEqual(MoveDirection.down.holdTarget, 0)
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
