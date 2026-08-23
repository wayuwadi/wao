#!/usr/bin/env swift

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

guard CommandLine.arguments.count == 5,
      let columns = Int(CommandLine.arguments[3]),
      let rows = Int(CommandLine.arguments[4]),
      columns > 0,
      rows > 0
else {
  fputs("usage: normalize-sprite-atlas.swift INPUT OUTPUT COLUMNS ROWS\n", stderr)
  exit(2)
}

let inputURL = URL(fileURLWithPath: CommandLine.arguments[1])
let outputURL = URL(fileURLWithPath: CommandLine.arguments[2])
guard
  let source = CGImageSourceCreateWithURL(inputURL as CFURL, nil),
  let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
else {
  fputs("unable to read sprite atlas\n", stderr)
  exit(1)
}

let width = image.width
let height = image.height
let sourceBytesPerRow = width * 4
var sourcePixels = [UInt8](repeating: 0, count: height * sourceBytesPerRow)
let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
guard let sourceContext = CGContext(
  data: &sourcePixels,
  width: width,
  height: height,
  bitsPerComponent: 8,
  bytesPerRow: sourceBytesPerRow,
  space: CGColorSpaceCreateDeviceRGB(),
  bitmapInfo: bitmapInfo.rawValue
) else {
  fputs("unable to create source bitmap\n", stderr)
  exit(1)
}
sourceContext.interpolationQuality = .none
sourceContext.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))

struct Component {
  let area: Int
  let minX: Int
  let minY: Int
  let maxX: Int
  let maxY: Int
  let centerX: Double
  let centerY: Double
}

var labels = [Int](repeating: -1, count: width * height)
var components = [Component]()
var queue = [Int]()

for index in labels.indices where labels[index] == -1 && sourcePixels[index * 4 + 3] > 0 {
  let componentID = components.count
  labels[index] = componentID
  queue.removeAll(keepingCapacity: true)
  queue.append(index)
  var cursor = 0
  var minX = width
  var minY = height
  var maxX = 0
  var maxY = 0
  var sumX = 0
  var sumY = 0

  while cursor < queue.count {
    let current = queue[cursor]
    cursor += 1
    let x = current % width
    let y = current / width
    minX = min(minX, x)
    minY = min(minY, y)
    maxX = max(maxX, x)
    maxY = max(maxY, y)
    sumX += x
    sumY += y

    for deltaY in -1...1 {
      for deltaX in -1...1 where deltaX != 0 || deltaY != 0 {
        let nextX = x + deltaX
        let nextY = y + deltaY
        guard nextX >= 0, nextX < width, nextY >= 0, nextY < height else { continue }
        let next = nextY * width + nextX
        if labels[next] == -1 && sourcePixels[next * 4 + 3] > 0 {
          labels[next] = componentID
          queue.append(next)
        }
      }
    }
  }

  components.append(Component(
    area: queue.count,
    minX: minX,
    minY: minY,
    maxX: maxX,
    maxY: maxY,
    centerX: Double(sumX) / Double(queue.count),
    centerY: Double(sumY) / Double(queue.count)
  ))
}

let mainComponentIDs = components.indices.filter { components[$0].area >= 8_000 }
guard mainComponentIDs.count >= columns * rows - 1 else {
  fputs("expected at least \(columns * rows - 1) primary sprites, found \(mainComponentIDs.count)\n", stderr)
  exit(1)
}

func clusterCenters(values: [Double], count: Int) -> [Double] {
  let minimum = values.min()!
  let maximum = values.max()!
  var centers = (0..<count).map { index in
    minimum + (maximum - minimum) * Double(index) / Double(max(1, count - 1))
  }

  for _ in 0..<32 {
    var sums = [Double](repeating: 0, count: count)
    var totals = [Int](repeating: 0, count: count)
    for value in values {
      let cluster = centers.indices.min(by: { abs(value - centers[$0]) < abs(value - centers[$1]) })!
      sums[cluster] += value
      totals[cluster] += 1
    }
    for index in centers.indices where totals[index] > 0 {
      centers[index] = sums[index] / Double(totals[index])
    }
  }
  return centers.sorted()
}

let xCenters = clusterCenters(values: mainComponentIDs.map { components[$0].centerX }, count: columns)
let yCenters = clusterCenters(values: mainComponentIDs.map { components[$0].centerY }, count: rows)
var mainFrameByComponent = [Int: Int]()
var mainComponentByFrame = [Int: Int]()

for componentID in mainComponentIDs {
  let component = components[componentID]
  let column = xCenters.indices.min(by: {
    abs(component.centerX - xCenters[$0]) < abs(component.centerX - xCenters[$1])
  })!
  let row = yCenters.indices.min(by: {
    abs(component.centerY - yCenters[$0]) < abs(component.centerY - yCenters[$1])
  })!
  let frame = row * columns + column
  guard mainComponentByFrame[frame] == nil else {
    fputs("multiple primary sprites mapped to frame \(frame)\n", stderr)
    exit(1)
  }
  mainFrameByComponent[componentID] = frame
  mainComponentByFrame[frame] = componentID
}

var frameByComponent = [Int](repeating: -1, count: components.count)
for componentID in components.indices {
  if let frame = mainFrameByComponent[componentID] {
    frameByComponent[componentID] = frame
    continue
  }
  let component = components[componentID]
  let nearestMain = mainComponentIDs.min {
    let left = components[$0]
    let right = components[$1]
    let leftDistance = pow(component.centerX - left.centerX, 2) + pow(component.centerY - left.centerY, 2)
    let rightDistance = pow(component.centerX - right.centerX, 2) + pow(component.centerY - right.centerY, 2)
    return leftDistance < rightDistance
  }!
  frameByComponent[componentID] = mainFrameByComponent[nearestMain]!
}

struct Bounds {
  var minX = Int.max
  var minY = Int.max
  var maxX = Int.min
  var maxY = Int.min

  var width: Int { maxX - minX + 1 }
  var height: Int { maxY - minY + 1 }
  var isEmpty: Bool { minX == Int.max }

  mutating func include(_ component: Component) {
    minX = min(minX, component.minX)
    minY = min(minY, component.minY)
    maxX = max(maxX, component.maxX)
    maxY = max(maxY, component.maxY)
  }
}

var boundsByFrame = [Bounds](repeating: Bounds(), count: columns * rows)
for componentID in components.indices {
  boundsByFrame[frameByComponent[componentID]].include(components[componentID])
}

var sourceFrameByDestination = Array(0..<(columns * rows))
for frame in sourceFrameByDestination.indices where boundsByFrame[frame].isEmpty {
  let row = frame / columns
  let candidates = (0..<columns).map { row * columns + $0 }
  let populatedCandidates = candidates.filter { !boundsByFrame[$0].isEmpty }
  let nearest = populatedCandidates.min(by: { left, right in
    abs(left - frame) < abs(right - frame)
  })!
  sourceFrameByDestination[frame] = nearest
  print("frame \(frame) is empty; duplicating frame \(nearest)")
}

let cellSize = 256
let padding = 16
let baseline = cellSize - padding
let mainComponents = mainComponentIDs.map { components[$0] }
let populatedFrames = mainComponentByFrame.keys.sorted()
let maxHorizontalExtent = populatedFrames.map { frame in
  let main = components[mainComponentByFrame[frame]!]
  let bounds = boundsByFrame[frame]
  return max(main.centerX - Double(bounds.minX), Double(bounds.maxX) - main.centerX)
}.max()!
let scale = min(
  Double(cellSize - padding * 2) / Double(mainComponents.map { $0.maxX - $0.minX + 1 }.max()!),
  Double(cellSize - padding * 2) / Double(mainComponents.map { $0.maxY - $0.minY + 1 }.max()!),
  Double(cellSize / 2 - padding) / maxHorizontalExtent
)

let outputWidth = columns * cellSize
let outputHeight = rows * cellSize
let outputBytesPerRow = outputWidth * 4
var outputPixels = [UInt8](repeating: 0, count: outputHeight * outputBytesPerRow)

for destinationFrame in sourceFrameByDestination.indices {
  let sourceFrame = sourceFrameByDestination[destinationFrame]
  let mainComponent = components[mainComponentByFrame[sourceFrame]!]
  let column = destinationFrame % columns
  let row = destinationFrame / columns
  let cellX = column * cellSize
  let cellY = row * cellSize

  for localY in 0..<cellSize {
    let sourceY = Int((Double(mainComponent.maxY) + Double(localY - baseline) / scale).rounded())
    guard sourceY >= 0, sourceY < height else { continue }
    for localX in 0..<cellSize {
      let sourceX = Int((mainComponent.centerX + Double(localX - cellSize / 2) / scale).rounded())
      guard sourceX >= 0, sourceX < width else { continue }
      let sourcePixel = sourceY * width + sourceX
      let componentID = labels[sourcePixel]
      guard componentID >= 0, frameByComponent[componentID] == sourceFrame else { continue }
      let sourceOffset = sourceY * sourceBytesPerRow + sourceX * 4
      let targetOffset = (cellY + localY) * outputBytesPerRow + (cellX + localX) * 4
      outputPixels[targetOffset] = sourcePixels[sourceOffset]
      outputPixels[targetOffset + 1] = sourcePixels[sourceOffset + 1]
      outputPixels[targetOffset + 2] = sourcePixels[sourceOffset + 2]
      outputPixels[targetOffset + 3] = sourcePixels[sourceOffset + 3]
    }
  }
}

guard let outputContext = CGContext(
  data: &outputPixels,
  width: outputWidth,
  height: outputHeight,
  bitsPerComponent: 8,
  bytesPerRow: outputBytesPerRow,
  space: CGColorSpaceCreateDeviceRGB(),
  bitmapInfo: bitmapInfo.rawValue
), let outputImage = outputContext.makeImage(),
      let destination = CGImageDestinationCreateWithURL(
        outputURL as CFURL,
        UTType.png.identifier as CFString,
        1,
        nil
      )
else {
  fputs("unable to create normalized PNG\n", stderr)
  exit(1)
}

CGImageDestinationAddImage(destination, outputImage, nil)
guard CGImageDestinationFinalize(destination) else {
  fputs("unable to write normalized PNG\n", stderr)
  exit(1)
}

print("x centers: \(xCenters.map { Int($0.rounded()) })")
print("y centers: \(yCenters.map { Int($0.rounded()) })")
print("normalized \(columns * rows) frames to \(outputWidth)x\(outputHeight), scale \(String(format: "%.3f", scale))")
