// Turns the frames captured by the demo game test into listing videos.
//
//   CHATSPELLCHECK_DEMO=$PWD/build/demo/manifest.tsv ./gradlew runClientGameTest
//   swift tools/make-demo-video.swift build/demo/manifest.tsv docs/icon.png build/demo
//
// Writes demo-landscape.mp4 (1920x1080, Reddit/YouTube/Modrinth) and demo-vertical.mp4
// (1080x1920, Shorts/TikTok). Each manifest line is: png, hold ms, cursor x, cursor y, caption.

import AppKit
import AVFoundation

let args = CommandLine.arguments
guard args.count == 4 else {
	print("usage: make-demo-video.swift manifest.tsv icon.png out-dir")
	exit(2)
}
let fps: Int32 = 30
// The chat box lives in the bottom-left corner of a 1920x1080 capture; zoom in on it.
let crop = CGRect(x: 0, y: 504, width: 1024, height: 576)  // top-left origin, source pixels
let pink = NSColor(red: 0.91, green: 0.36, blue: 0.54, alpha: 1)
let lilac = NSColor(red: 0.97, green: 0.95, blue: 1, alpha: 1)

struct Frame { let image: CGImage; let hold: Int; let cursor: CGPoint?; let caption: String }

func load(_ path: String) -> CGImage {
	guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
		  let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { fatalError("cannot read \(path)") }
	return img
}

let frames: [Frame] = try! String(contentsOfFile: args[1], encoding: .utf8)
	.split(separator: "\n").map { line in
		let f = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
		let x = Double(f[2])!, y = Double(f[3])!
		return Frame(image: load(f[0]), hold: Int(f[1])!, cursor: x < 0 ? nil : CGPoint(x: x, y: y),
					 caption: f.count > 4 ? f[4] : "")
	}
let icon = load(args[2])

/// Draws in a top-left-origin context so layout reads like the screenshots.
func render(width: Int, height: Int, _ draw: (CGContext) -> Void) -> CVPixelBuffer {
	var buffer: CVPixelBuffer?
	CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32ARGB,
						[kCVPixelBufferCGImageCompatibilityKey: true, kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary,
						&buffer)
	CVPixelBufferLockBaseAddress(buffer!, [])
	let ctx = CGContext(data: CVPixelBufferGetBaseAddress(buffer!), width: width, height: height, bitsPerComponent: 8,
						bytesPerRow: CVPixelBufferGetBytesPerRow(buffer!), space: CGColorSpaceCreateDeviceRGB(),
						bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue)!
	ctx.translateBy(x: 0, y: CGFloat(height))
	ctx.scaleBy(x: 1, y: -1)
	ctx.interpolationQuality = .none  // keep Minecraft's pixels crisp
	NSGraphicsContext.saveGraphicsState()
	NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: true)
	draw(ctx)
	NSGraphicsContext.restoreGraphicsState()
	CVPixelBufferUnlockBaseAddress(buffer!, [])
	return buffer!
}

func drawImage(_ ctx: CGContext, _ img: CGImage, in rect: CGRect) {
	// CGContext.draw expects bottom-left origin; flip locally.
	ctx.saveGState()
	ctx.translateBy(x: rect.minX, y: rect.maxY)
	ctx.scaleBy(x: 1, y: -1)
	ctx.draw(img, in: CGRect(origin: .zero, size: rect.size))
	ctx.restoreGState()
}

func drawGame(_ ctx: CGContext, _ frame: Frame, in rect: CGRect) {
	let cropped = frame.image.cropping(to: crop)!
	drawImage(ctx, cropped, in: rect)
	guard let c = frame.cursor else { return }
	let s = rect.width / crop.width
	let p = CGPoint(x: rect.minX + (c.x - crop.minX) * s, y: rect.minY + (c.y - crop.minY) * s)
	let k = s * 1.6
	let arrow = [(0.0, 0.0), (0.0, 17.0), (4.0, 13.0), (7.0, 20.0), (10.0, 19.0), (7.0, 12.0), (12.0, 12.0)]
	ctx.beginPath()
	ctx.move(to: CGPoint(x: p.x, y: p.y))
	for (x, y) in arrow.dropFirst() { ctx.addLine(to: CGPoint(x: p.x + x * k, y: p.y + y * k)) }
	ctx.closePath()
	ctx.setFillColor(.white)
	ctx.setStrokeColor(.black)
	ctx.setLineWidth(1.5 * k / 2)
	ctx.drawPath(using: .fillStroke)
}

func text(_ string: String, size: CGFloat, color: NSColor, weight: NSFont.Weight = .heavy) -> NSAttributedString {
	let style = NSMutableParagraphStyle()
	style.alignment = .center
	return NSAttributedString(string: string, attributes: [
		.font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color, .paragraphStyle: style])
}

func drawCentered(_ s: NSAttributedString, width: CGFloat, y: CGFloat) {
	let h = s.boundingRect(with: CGSize(width: width - 80, height: 1000), options: .usesLineFragmentOrigin).height
	s.draw(with: CGRect(x: 40, y: y, width: width - 80, height: h + 4), options: .usesLineFragmentOrigin)
}

func endCard(_ ctx: CGContext, width: CGFloat, height: CGFloat) {
	ctx.setFillColor(lilac.cgColor)
	ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
	let side = min(width, height) * 0.36
	drawImage(ctx, icon, in: CGRect(x: (width - side) / 2, y: height * 0.5 - side * 0.95, width: side, height: side))
	drawCentered(text("Chat Spellcheck", size: side * 0.3, color: pink), width: width, y: height * 0.5 + side * 0.15)
	drawCentered(text("Free · Fabric 26.2 / 26.3 · CurseForge & Modrinth", size: side * 0.11,
					  color: NSColor(white: 0.25, alpha: 1), weight: .semibold), width: width, y: height * 0.5 + side * 0.55)
}

func write(_ name: String, width: Int, height: Int, compose: (CGContext, Frame) -> Void) {
	let url = URL(fileURLWithPath: args[3]).appendingPathComponent(name)
	try? FileManager.default.removeItem(at: url)
	let writer = try! AVAssetWriter(outputURL: url, fileType: .mp4)
	let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
		AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
		AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 8_000_000]])
	let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: nil)
	writer.add(input)
	writer.startWriting()
	writer.startSession(atSourceTime: .zero)
	var t: Int64 = 0
	func append(_ buffer: CVPixelBuffer, ms: Int) {
		for _ in 0..<max(1, Int((Double(ms) * Double(fps) / 1000).rounded())) {
			while !input.isReadyForMoreMediaData { usleep(1000) }
			adaptor.append(buffer, withPresentationTime: CMTime(value: t, timescale: fps))
			t += 1
		}
	}
	for frame in frames {
		append(render(width: width, height: height) { compose($0, frame) }, ms: frame.hold)
	}
	append(render(width: width, height: height) { endCard($0, width: CGFloat(width), height: CGFloat(height)) }, ms: 2500)
	input.markAsFinished()
	let done = DispatchSemaphore(value: 0)
	writer.finishWriting { done.signal() }
	done.wait()
	print("\(url.path): \(t) frames, \(writer.status == .completed ? "ok" : "FAILED \(String(describing: writer.error))")")
}

write("demo-landscape.mp4", width: 1920, height: 1080) { ctx, frame in
	drawGame(ctx, frame, in: CGRect(x: 0, y: 0, width: 1920, height: 1080))
	if !frame.caption.isEmpty {
		ctx.setFillColor(NSColor(white: 0, alpha: 0.55).cgColor)
		ctx.fill(CGRect(x: 0, y: 0, width: 1920, height: 130))
		drawCentered(text(frame.caption, size: 64, color: .white), width: 1920, y: 26)
	}
}

write("demo-vertical.mp4", width: 1080, height: 1920) { ctx, frame in
	ctx.setFillColor(lilac.cgColor)
	ctx.fill(CGRect(x: 0, y: 0, width: 1080, height: 1920))
	drawImage(ctx, icon, in: CGRect(x: 440, y: 170, width: 200, height: 200))
	drawCentered(text("Chat Spellcheck", size: 96, color: pink), width: 1080, y: 400)
	drawCentered(text("for Minecraft chat", size: 52, color: NSColor(white: 0.3, alpha: 1), weight: .bold), width: 1080, y: 520)
	drawGame(ctx, frame, in: CGRect(x: 0, y: 660, width: 1080, height: 608))
	drawCentered(text(frame.caption, size: 70, color: NSColor(white: 0.12, alpha: 1)), width: 1080, y: 1340)
	drawCentered(text("Free on CurseForge & Modrinth", size: 46, color: pink, weight: .bold), width: 1080, y: 1700)
}
