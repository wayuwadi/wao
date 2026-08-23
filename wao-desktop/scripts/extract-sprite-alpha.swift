#!/usr/bin/env swift

import AppKit
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

guard CommandLine.arguments.count == 3 else {
  fputs("usage: extract-sprite-alpha.swift INPUT OUTPUT\n", stderr)
  exit(2)
}

let inputURL = URL(fileURLWithPath: CommandLine.arguments[1])
let outputURL = URL(fileURLWithPath: CommandLine.arguments[2])
guard
  let source = CGImageSourceCreateWithURL(inputURL as CFURL, nil),
  let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
else {
  fputs("unable to read input image\n", stderr)
  exit(1)
}

let width = image.width
let height = image.height
let bytesPerRow = width * 4
var pixels = [UInt8](repeating: 0, count: height * bytesPerRow)
let colorSpace = CGColorSpaceCreateDeviceRGB()
let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)

guard let context = CGContext(
  data: &pixels,
  width: width,
  height: height,
  bitsPerComponent: 8,
  bytesPerRow: bytesPerRow,
  space: colorSpace,
  bitmapInfo: bitmapInfo.rawValue
) else {
  fputs("unable to create bitmap context\n", stderr)
  exit(1)
}

context.interpolationQuality = .none
context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))

func isBackgroundCandidate(_ pixelIndex: Int) -> Bool {
  let offset = pixelIndex * 4
  let red = Int(pixels[offset])
  let green = Int(pixels[offset + 1])
  let blue = Int(pixels[offset + 2])
  let minimum = min(red, green, blue)
  let maximum = max(red, green, blue)
  return minimum >= 210 && maximum - minimum <= 18
}

var background = [Bool](repeating: false, count: width * height)
var queue = [Int]()
queue.reserveCapacity(width * height)

func enqueueIfBackground(_ x: Int, _ y: Int) {
  guard x >= 0, x < width, y >= 0, y < height else { return }
  let index = y * width + x
  guard !background[index], isBackgroundCandidate(index) else { return }
  background[index] = true
  queue.append(index)
}

for x in 0..<width {
  enqueueIfBackground(x, 0)
  enqueueIfBackground(x, height - 1)
}
for y in 0..<height {
  enqueueIfBackground(0, y)
  enqueueIfBackground(width - 1, y)
}

var cursor = 0
while cursor < queue.count {
  let index = queue[cursor]
  cursor += 1
  let x = index % width
  let y = index / width
  enqueueIfBackground(x - 1, y)
  enqueueIfBackground(x + 1, y)
  enqueueIfBackground(x, y - 1)
  enqueueIfBackground(x, y + 1)
}

for index in background.indices where background[index] {
  let offset = index * 4
  pixels[offset] = 0
  pixels[offset + 1] = 0
  pixels[offset + 2] = 0
  pixels[offset + 3] = 0
}

guard let outputImage = context.makeImage() else {
  fputs("unable to create output image\n", stderr)
  exit(1)
}
guard let destination = CGImageDestinationCreateWithURL(
  outputURL as CFURL,
  UTType.png.identifier as CFString,
  1,
  nil
) else {
  fputs("unable to create PNG destination\n", stderr)
  exit(1)
}

CGImageDestinationAddImage(destination, outputImage, nil)
guard CGImageDestinationFinalize(destination) else {
  fputs("unable to write output PNG\n", stderr)
  exit(1)
}

print("removed \(queue.count) connected background pixels from \(width)x\(height) sprite sheet")
