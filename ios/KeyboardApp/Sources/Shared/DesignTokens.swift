import SwiftUI
import UIKit

/// Design tokens, spec section 1 — "chalkboard correction".
///
/// The same values as Android's `ui/theme/Color.kt` and `res/values/colors.xml`. If one
/// changes, all three change. There are no other colours in the product.
enum ChalkColor {
    static let bgSlate = UIColor(red: 0x23 / 255, green: 0x2B / 255, blue: 0x2B / 255, alpha: 1)
    static let surfaceKey = UIColor(red: 0x34 / 255, green: 0x40 / 255, blue: 0x3F / 255, alpha: 1)
    static let surfaceKeyPressed = UIColor(red: 0x3E / 255, green: 0x4C / 255, blue: 0x4B / 255, alpha: 1)
    static let surfaceKeySpecial = UIColor(red: 0x2C / 255, green: 0x37 / 255, blue: 0x36 / 255, alpha: 1)
    static let textChalk = UIColor(red: 0xF5 / 255, green: 0xF3 / 255, blue: 0xEA / 255, alpha: 1)
    static let textChalkMuted = UIColor(red: 0xA9 / 255, green: 0xB0 / 255, blue: 0xAC / 255, alpha: 1)
    static let accentChalkYellow = UIColor(red: 0xE8 / 255, green: 0xC4 / 255, blue: 0x68 / 255, alpha: 1)
    static let accentDustyTeal = UIColor(red: 0x5B / 255, green: 0x92 / 255, blue: 0x79 / 255, alpha: 1)
    /// Grammar-error underline ONLY. Never decorative.
    static let accentSoftCoral = UIColor(red: 0xE2 / 255, green: 0x72 / 255, blue: 0x5B / 255, alpha: 1)
    static let surfacePaper = UIColor(red: 0xF4 / 255, green: 0xF1 / 255, blue: 0xEA / 255, alpha: 1)
    static let surfacePaperCard = UIColor.white
    static let textInk = UIColor(red: 0x2B / 255, green: 0x2B / 255, blue: 0x28 / 255, alpha: 1)
    static let textInkMuted = UIColor(red: 0x6B / 255, green: 0x68 / 255, blue: 0x5F / 255, alpha: 1)
    static let borderHairline = UIColor(red: 0xDA / 255, green: 0xD5 / 255, blue: 0xC8 / 255, alpha: 1)
    static let stateError = UIColor(red: 0xC0 / 255, green: 0x39 / 255, blue: 0x2B / 255, alpha: 1)
}

/// Spacing, 4pt base unit. 4/8/12/16/24/32 only — no arbitrary values.
enum ChalkSpacing {
    static let x1: CGFloat = 4
    static let x2: CGFloat = 8
    static let x3: CGFloat = 12
    static let x4: CGFloat = 16
    static let x6: CGFloat = 24
    static let x8: CGFloat = 32
    /// Accessibility floor. Applies to keyboard keys too.
    static let minTouchTarget: CGFloat = 44
}

enum ChalkRadius {
    static let key: CGFloat = 8
    static let card: CGFloat = 12
    static let suggestionStrip: CGFloat = 20
}

/// Type scale. Inter for all functional text; the signature face at 28pt and up only,
/// never in the keyboard itself.
enum ChalkFont {
    static func inter(_ size: CGFloat, weight: UIFont.Weight = .regular) -> UIFont {
        UIFont(name: interName(weight), size: size) ?? .systemFont(ofSize: size, weight: weight)
    }

    private static func interName(_ weight: UIFont.Weight) -> String {
        switch weight {
        case .semibold, .bold: return "Inter-SemiBold"
        case .medium: return "Inter-Medium"
        default: return "Inter-Regular"
        }
    }

    /// Onboarding headline and empty states only. Never body copy, never buttons.
    static func display(_ size: CGFloat = 32) -> UIFont {
        UIFont(name: "Caveat-SemiBold", size: size) ?? .systemFont(ofSize: size, weight: .semibold)
    }

    static var title: UIFont { inter(22, weight: .semibold) }
    static var body: UIFont { inter(16) }
    static var label: UIFont { inter(14, weight: .medium) }
    static var caption: UIFont { inter(12) }
    static var keyLabel: UIFont { inter(18, weight: .medium) }
    static var suggestion: UIFont { inter(15, weight: .medium) }
    static var suggestionTop: UIFont { inter(15, weight: .semibold) }
}

/// Motion tokens, section 1.4. The chalk-redraw is composed from these.
enum ChalkMotion {
    static let standard: TimeInterval = 0.150
    static let suggestionSwap: TimeInterval = 0.100

    static let chalkUnderline: TimeInterval = 0.150
    static let chalkCrossfade: TimeInterval = 0.100
    static let chalkSettle: TimeInterval = 0.200
    static var chalkTotal: TimeInterval { chalkUnderline + chalkCrossfade + chalkSettle }
    static let chalkStagger: TimeInterval = 0.080

    /// Hard requirement, not polish: with Reduce Motion on, the sequence collapses to an
    /// instant swap with no underline at all — it is not merely shortened.
    static var reducedMotion: Bool { UIAccessibility.isReduceMotionEnabled }

    static func duration(_ value: TimeInterval) -> TimeInterval {
        reducedMotion ? 0 : value
    }
}
