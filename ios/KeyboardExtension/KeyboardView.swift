import UIKit

enum KeyAction {
    case character(String)
    case space
    case backspace
    case enter
    case shift
    case switchPage
    case nextKeyboard
    case fixGrammar
    case rewrite
    case suggestion(String)
}

/// The keyboard surface. Mirrors the Android visual spec exactly: slate background, flat
/// (no shadow — it is slate, not a floating card), 8pt key corners, 44pt minimum touch
/// target, suggestion strip above the keys.
///
/// The correction panel and the status line share the strip's slot; only one is ever
/// visible.
final class KeyboardView: UIView {

    private let onKey: (KeyAction) -> Void
    private let showRewriteKey: Bool

    private let suggestionStrip = UIStackView()
    private let statusLabel = UILabel()
    private let correctionView = CorrectionView()
    private var keyRows: [UIStackView] = []

    private var shifted = true
    private var page = 0

    init(showRewriteKey: Bool, onKey: @escaping (KeyAction) -> Void) {
        self.showRewriteKey = showRewriteKey
        self.onKey = onKey
        super.init(frame: .zero)
        backgroundColor = ChalkColor.bgSlate
        buildLayout()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    // MARK: - Layout

    private func buildLayout() {
        suggestionStrip.axis = .horizontal
        suggestionStrip.distribution = .fillEqually

        statusLabel.font = ChalkFont.label
        statusLabel.textColor = ChalkColor.textChalkMuted
        statusLabel.isHidden = true

        correctionView.isHidden = true

        let stack = UIStackView(arrangedSubviews: [
            suggestionStrip, statusLabel, correctionView,
        ])
        stack.axis = .vertical
        stack.spacing = 0
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        let keys = UIStackView()
        keys.axis = .vertical
        keys.spacing = ChalkSpacing.x1
        keys.distribution = .fillEqually
        keys.translatesAutoresizingMaskIntoConstraints = false
        addSubview(keys)

        for row in currentLayout() {
            let rowStack = UIStackView()
            rowStack.axis = .horizontal
            rowStack.spacing = ChalkSpacing.x1
            rowStack.distribution = .fillProportionally
            row.forEach { rowStack.addArrangedSubview(makeKey($0)) }
            keyRows.append(rowStack)
            keys.addArrangedSubview(rowStack)
        }

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.heightAnchor.constraint(equalToConstant: ChalkSpacing.minTouchTarget),

            keys.topAnchor.constraint(equalTo: stack.bottomAnchor, constant: ChalkSpacing.x1),
            keys.leadingAnchor.constraint(equalTo: leadingAnchor, constant: ChalkSpacing.x1),
            keys.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -ChalkSpacing.x1),
            keys.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -ChalkSpacing.x1),
        ])
    }

    private struct KeySpec {
        let title: String
        let action: KeyAction
        let special: Bool
        let weight: CGFloat

        init(_ title: String, _ action: KeyAction, special: Bool = false, weight: CGFloat = 1) {
            self.title = title
            self.action = action
            self.special = special
            self.weight = weight
        }
    }

    private func currentLayout() -> [[KeySpec]] {
        let letters = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
        let symbols = ["1234567890", "@#$%&-+()", "*\"':;!?"]
        let rows = page == 0 ? letters : symbols

        var layout: [[KeySpec]] = rows.enumerated().map { index, row in
            var specs = row.map { KeySpec(String($0), .character(String($0))) }
            if index == 2 {
                specs.insert(
                    KeySpec(page == 0 ? "⇧" : "=\\<", page == 0 ? .shift : .switchPage,
                            special: true, weight: 1.5),
                    at: 0
                )
                specs.append(KeySpec("⌫", .backspace, special: true, weight: 1.5))
            }
            return specs
        }

        // Bottom row. The rewrite key is omitted entirely when no provider is configured
        // and the space bar reclaims its width — no disabled ghost button.
        var bottom = [
            KeySpec(page == 0 ? "?123" : "ABC", .switchPage, special: true, weight: 1.5),
            KeySpec("✓", .fixGrammar, special: true),
        ]
        if showRewriteKey {
            bottom.append(KeySpec("✎", .rewrite, special: true))
        }
        bottom.append(KeySpec("", .space, weight: showRewriteKey ? 4 : 5))
        bottom.append(KeySpec(".", .character(".")))
        bottom.append(KeySpec("⏎", .enter, special: true, weight: 2))
        layout.append(bottom)

        return layout
    }

    private func makeKey(_ spec: KeySpec) -> UIView {
        let button = UIButton(type: .system)
        button.setTitle(displayTitle(spec), for: .normal)
        button.titleLabel?.font = ChalkFont.keyLabel
        button.setTitleColor(ChalkColor.textChalk, for: .normal)
        button.backgroundColor = spec.special ? ChalkColor.surfaceKeySpecial : ChalkColor.surfaceKey
        button.layer.cornerRadius = ChalkRadius.key
        button.accessibilityLabel = accessibilityLabel(spec)

        // Accessibility floor, section 1.6: nothing below 44pt, keys included.
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: ChalkSpacing.minTouchTarget)
            .isActive = true
        button.setContentHuggingPriority(.defaultLow, for: .horizontal)

        button.addAction(UIAction { [weak self] _ in self?.press(spec) }, for: .touchUpInside)
        button.addAction(UIAction { _ in
            button.backgroundColor = ChalkColor.surfaceKeyPressed
        }, for: .touchDown)
        button.addAction(UIAction { _ in
            UIView.animate(withDuration: ChalkMotion.duration(ChalkMotion.standard)) {
                button.backgroundColor = spec.special
                    ? ChalkColor.surfaceKeySpecial : ChalkColor.surfaceKey
            }
        }, for: [.touchUpInside, .touchUpOutside, .touchCancel])

        return button
    }

    private func displayTitle(_ spec: KeySpec) -> String {
        if case .character(let text) = spec.action, page == 0 {
            return shifted ? text.uppercased() : text
        }
        return spec.title
    }

    private func accessibilityLabel(_ spec: KeySpec) -> String {
        switch spec.action {
        case .shift: return "Shift"
        case .backspace: return "Backspace"
        case .space: return "Space"
        case .enter: return "Enter"
        case .fixGrammar: return "Fix grammar"
        case .rewrite: return "Rewrite with AI"
        case .switchPage: return page == 0 ? "Symbols" : "Letters"
        default: return displayTitle(spec)
        }
    }

    private func press(_ spec: KeySpec) {
        switch spec.action {
        case .shift:
            shifted.toggle()
            rebuildTitles()
        case .switchPage:
            page = page == 0 ? 1 : 0
            rebuild()
        case .character(let text):
            onKey(.character(page == 0 && shifted ? text.uppercased() : text))
            if shifted { shifted = false; rebuildTitles() }
        default:
            onKey(spec.action)
        }
    }

    private func rebuild() {
        keyRows.forEach { $0.removeFromSuperview() }
        keyRows.removeAll()
        buildLayout()
    }

    private func rebuildTitles() {
        // Titles depend on shift; the layout does not, so only the labels are refreshed.
        for (rowIndex, row) in currentLayout().enumerated() {
            guard rowIndex < keyRows.count else { break }
            for (keyIndex, spec) in row.enumerated() {
                let button = keyRows[rowIndex].arrangedSubviews[keyIndex] as? UIButton
                button?.setTitle(displayTitle(spec), for: .normal)
            }
        }
    }

    // MARK: - Strip, status, correction

    func setSuggestions(_ words: [String]) {
        suggestionStrip.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (index, word) in words.enumerated() {
            let button = UIButton(type: .system)
            button.setTitle(word, for: .normal)
            // Rank reads by weight as well as colour, so it never depends on colour alone.
            button.titleLabel?.font = index == 0 ? ChalkFont.suggestionTop : ChalkFont.suggestion
            button.setTitleColor(
                index == 0 ? ChalkColor.accentChalkYellow : ChalkColor.textChalkMuted,
                for: .normal
            )
            button.addAction(UIAction { [weak self] _ in
                self?.onKey(.suggestion(word))
            }, for: .touchUpInside)
            suggestionStrip.addArrangedSubview(button)
        }
        showStrip()
    }

    func showStatus(_ message: String) {
        statusLabel.text = message
        statusLabel.isHidden = false
        suggestionStrip.isHidden = true
        correctionView.isHidden = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            self?.showStrip()
        }
    }

    func playChalkRedraw(
        text: String, corrections: [Correction], completion: @escaping () -> Void
    ) {
        suggestionStrip.isHidden = true
        statusLabel.isHidden = true
        correctionView.isHidden = false
        correctionView.play(text: text, corrections: corrections) { [weak self] in
            completion()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { self?.showStrip() }
        }
    }

    private func showStrip() {
        suggestionStrip.isHidden = false
        statusLabel.isHidden = true
        correctionView.isHidden = true
    }
}

/// The chalk-redraw correction, spec section 1.4.
///
/// Per corrected word: a coral underline drawn left-to-right (150ms, a stroke reveal, not
/// a fade), the word cross-fading to its correction (100ms), then the underline shifting
/// coral to chalk yellow and fading out (200ms). Words are staggered 80ms so a multi-word
/// fix reads left-to-right rather than firing at once.
///
/// This exists because a keyboard extension cannot draw into the host app's text field.
/// The correction is shown and animated here, in a view the keyboard owns, and only then
/// committed.
final class CorrectionView: UIView {

    private struct Span {
        let original: String
        let corrected: String?
        var frame: CGRect = .zero
        var progress: CGFloat = 0
    }

    private var spans: [Span] = []
    private var displayLink: CADisplayLink?
    private var startedAt: CFTimeInterval = 0
    private var totalDuration: CFTimeInterval = 0
    private var onFinished: (() -> Void)?

    private let textFont = ChalkFont.body
    private let correctedFont = ChalkFont.inter(16, weight: .semibold)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = ChalkColor.bgSlate
        isOpaque = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    func play(text: String, corrections: [Correction], completion: @escaping () -> Void) {
        let ordered = corrections.filter { !$0.isNoOp }
            .sorted { $0.range.lowerBound < $1.range.lowerBound }
        spans = buildSpans(text: text, corrections: ordered)
        onFinished = completion

        let corrected = applyCorrections(to: text, ordered)
        UIAccessibility.post(
            notification: .announcement,
            argument: "\(ordered.count) corrections applied. \(corrected)"
        )

        let animatedCount = spans.filter { $0.corrected != nil }.count
        guard animatedCount > 0, !ChalkMotion.reducedMotion else {
            // Instant swap: no underline, no stagger.
            for index in spans.indices { spans[index].progress = 1 }
            setNeedsDisplay()
            completion()
            return
        }

        totalDuration = ChalkMotion.chalkTotal
            + ChalkMotion.chalkStagger * Double(animatedCount - 1)
        startedAt = CACurrentMediaTime()

        displayLink?.invalidate()
        let link = CADisplayLink(target: self, selector: #selector(step))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    @objc private func step() {
        let elapsed = CACurrentMediaTime() - startedAt
        var index = 0
        for spanIndex in spans.indices {
            guard spans[spanIndex].corrected != nil else {
                spans[spanIndex].progress = 1
                continue
            }
            let start = ChalkMotion.chalkStagger * Double(index)
            let progress = (elapsed - start) / ChalkMotion.chalkTotal
            spans[spanIndex].progress = CGFloat(min(max(progress, 0), 1))
            index += 1
        }
        setNeedsDisplay()

        if elapsed >= totalDuration {
            displayLink?.invalidate()
            displayLink = nil
            onFinished?()
            onFinished = nil
        }
    }

    private func buildSpans(text: String, corrections: [Correction]) -> [Span] {
        let characters = Array(text)
        var result: [Span] = []
        var cursor = 0
        for correction in corrections {
            let start = min(max(correction.range.lowerBound, 0), characters.count)
            let end = min(max(correction.range.upperBound, start), characters.count)
            if start > cursor {
                result.append(Span(original: String(characters[cursor..<start]), corrected: nil))
            }
            result.append(
                Span(original: String(characters[start..<end]), corrected: correction.replacement)
            )
            cursor = end
        }
        if cursor < characters.count {
            result.append(Span(original: String(characters[cursor...]), corrected: nil))
        }
        return result
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }

        // Laid out at each span's widest so nothing reflows mid-animation.
        var x = ChalkSpacing.x4
        let baselineY = rect.midY - textFont.lineHeight / 2

        for index in spans.indices {
            let span = spans[index]
            let plainWidth = (span.original as NSString)
                .size(withAttributes: [.font: textFont]).width
            let correctedWidth = span.corrected.map {
                ($0 as NSString).size(withAttributes: [.font: correctedFont]).width
            } ?? 0
            let width = max(plainWidth, correctedWidth)
            spans[index].frame = CGRect(x: x, y: baselineY, width: width, height: textFont.lineHeight)
            x += width
        }

        for span in spans {
            draw(span: span, in: context)
        }
    }

    private func draw(span: Span, in context: CGContext) {
        guard let corrected = span.corrected else {
            (span.original as NSString).draw(
                at: span.frame.origin,
                withAttributes: [.font: textFont, .foregroundColor: ChalkColor.textChalk]
            )
            return
        }

        let stages = stageProgress(span.progress)

        if stages.crossfade < 1 {
            (span.original as NSString).draw(
                at: span.frame.origin,
                withAttributes: [
                    .font: textFont,
                    .foregroundColor: ChalkColor.textChalk.withAlphaComponent(1 - stages.crossfade),
                ]
            )
        }
        if stages.crossfade > 0 {
            // Corrected words keep the heavier weight after the colour fades, so the
            // change never depends on colour alone.
            (corrected as NSString).draw(
                at: span.frame.origin,
                withAttributes: [
                    .font: correctedFont,
                    .foregroundColor: ChalkColor.textChalk.withAlphaComponent(stages.crossfade),
                ]
            )
        }

        guard stages.underlineExtent > 0, stages.underlineAlpha > 0 else { return }
        let y = span.frame.maxY + 2
        let colour = blend(
            ChalkColor.accentSoftCoral, ChalkColor.accentChalkYellow, stages.underlineTint
        )
        context.setStrokeColor(colour.withAlphaComponent(stages.underlineAlpha).cgColor)
        context.setLineWidth(2)
        context.setLineCap(.round)
        context.move(to: CGPoint(x: span.frame.minX, y: y))
        context.addLine(to: CGPoint(x: span.frame.minX + span.frame.width * stages.underlineExtent, y: y))
        context.strokePath()
    }

    private struct Stages {
        let underlineExtent: CGFloat
        let underlineAlpha: CGFloat
        let underlineTint: CGFloat
        let crossfade: CGFloat
    }

    private func stageProgress(_ progress: CGFloat) -> Stages {
        let total = CGFloat(ChalkMotion.chalkTotal)
        let drawEnd = CGFloat(ChalkMotion.chalkUnderline) / total
        let fadeEnd = drawEnd + CGFloat(ChalkMotion.chalkCrossfade) / total

        let extent = ease(min(max(progress / drawEnd, 0), 1))
        let crossfade = ease(min(max((progress - drawEnd) / (fadeEnd - drawEnd), 0), 1))
        let settle = ease(min(max((progress - fadeEnd) / (1 - fadeEnd), 0), 1))

        return Stages(
            underlineExtent: extent,
            underlineAlpha: 1 - settle,
            underlineTint: settle,
            crossfade: crossfade
        )
    }

    /// Approximates the ease-out curve from the motion tokens.
    private func ease(_ t: CGFloat) -> CGFloat {
        1 - pow(1 - t, 3)
    }

    private func blend(_ from: UIColor, _ to: UIColor, _ amount: CGFloat) -> UIColor {
        var fr: CGFloat = 0, fg: CGFloat = 0, fb: CGFloat = 0, fa: CGFloat = 0
        var tr: CGFloat = 0, tg: CGFloat = 0, tb: CGFloat = 0, ta: CGFloat = 0
        from.getRed(&fr, green: &fg, blue: &fb, alpha: &fa)
        to.getRed(&tr, green: &tg, blue: &tb, alpha: &ta)
        return UIColor(
            red: fr + (tr - fr) * amount,
            green: fg + (tg - fg) * amount,
            blue: fb + (tb - fb) * amount,
            alpha: 1
        )
    }
}
