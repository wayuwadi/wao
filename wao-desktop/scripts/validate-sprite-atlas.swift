#!/usr/bin/env swift

import CoreGraphics
import Foundation
import ImageIO

guard CommandLine.arguments.count == 4,
      let columns = Int(CommandLine.arguments[2]),
      let rows = Int(CommandLine.arguments[3]),
      columns > 0,
      rows > 0
else {
  fputs("usage: validate-sprite-atlas.swift INPUT COLUMNS ROWS\n", stderr)
  exit(2)
}

let inputURL = URL(fileURLWithPath: CommandLine.arguments[1])
guard
  let source = CGImageSourceCreateWithURL(inputURL as CFURL, nil),
  let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
else {
  fputs("unable to read sprite atlas\n", stderr)
  exit(1)
}

let width = image.width
let height = image.height
let bytesPerRow = width * 4
var pixels = [UInt8](repeating: 0, count: height * bytesPerRow)
let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
guard let context = CGContext(
  data: &pixels,
  width: width,
  height: height,
  bitsPerComponent: 8,
  bytesPerRow: bytesPerRow,
  space: CGColorSpaceCreateDeviceRGB(),
  bitmapInfo: bitmapInfo.rawValue
) else {
  fputs("unable to create bitmap context\n", stderr)
  exit(1)
}
context.interpolationQuality = .none
context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))

func alpha(_ x: Int, _ y: Int) -> UInt8 {
  pixels[y * bytesPerRow + x * 4 + 3]
}

var failed = false
for row in 0..<rows {
  for column in 0..<columns {
    let minX = column * width / columns
    let maxX = (column + 1) * width / columns
    let minY = row * height / rows
    let maxY = (row + 1) * height / rows
    var opaquePixels = 0
    var borderPixels = 0

    for y in minY..<maxY {
      for x in minX..<maxX where alpha(x, y) > 0 {
        opaquePixels += 1
        if x - minX < 2 || maxX - x <= 2 || y - minY < 2 || maxY - y <= 2 {
          borderPixels += 1
        }
      }
    }

    if opaquePixels == 0 || borderPixels > 0 {
      failed = true
      fputs("cell \(row),\(column) opaque=\(opaquePixels) border=\(borderPixels)\n", stderr)
    }
  }
}

if failed { exit(1) }
print("validated \(columns * rows) populated cells with transparent boundaries in \(width)x\(height) atlas")
