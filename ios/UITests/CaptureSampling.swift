import XCTest

/// What a capture of the screen shows where the app is: used by the tests that
/// look at the screenshot shield from the outside.
enum Capture {
    /// The share of `frame` (screen points) that is lit in a capture of the screen
    /// taken now. Sampled well inside the frame: a window's rounded corners and
    /// its edge show what is behind it, and on a phone the top and bottom of the
    /// screen carry the status bar and the home indicator, which the system draws
    /// whatever the app does.
    static func litShare(in frame: CGRect) -> Double {
        let shot = XCUIScreen.main.screenshot().image
        let image = shot.cgImage!
        let scale = CGFloat(image.width) / shot.size.width
        let width = image.width, height = image.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let context = CGContext(data: &bytes, width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        let inner = frame.insetBy(dx: frame.width * 0.12, dy: frame.height * 0.12)
        var lit = 0, seen = 0
        for y in stride(from: Int(inner.minY * scale), to: Int(inner.maxY * scale), by: 3) {
            for x in stride(from: Int(inner.minX * scale), to: Int(inner.maxX * scale), by: 3) {
                guard x >= 0, y >= 0, x < width, y < height else { continue }
                let i = (y * width + x) * 4
                seen += 1
                if bytes[i] > 80 || bytes[i + 1] > 80 || bytes[i + 2] > 80 { lit += 1 }
            }
        }
        return seen == 0 ? 1 : Double(lit) / Double(seen)
    }
}
