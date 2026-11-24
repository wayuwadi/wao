import type { ExcalidrawTextElement } from '@excalidraw/excalidraw/element/types'

export interface TextColorRange { start: number; end: number; color: string }
export interface DrawingTextStyle { source: string; background?: string; runs: TextColorRange[] }
type TextContent = Pick<ExcalidrawTextElement, 'text' | 'originalText' | 'customData'>
export const textStyleKey: 'waoTextStyle'
export function textSource(element: TextContent): string
export function contrastingText(color: string): string
export function readTextStyle(element: TextContent): DrawingTextStyle
export function setTextRange(runs: TextColorRange[], start: number, end: number, color?: string): TextColorRange[]
export function textSegments(text: string, runs: TextColorRange[], offset?: number): Array<{ start: number; end: number; text: string; color?: string }>
export function styleSvgText(node: SVGGElement, element: TextContent & Pick<ExcalidrawTextElement, 'width' | 'height' | 'fontSize'>, lines: string[]): void
