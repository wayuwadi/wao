import {
  Capacitor,
  registerPlugin,
  type PluginListenerHandle,
} from "@capacitor/core";
import type { CaptureDraft } from "../domain/noteCapture";
import type { Note } from "../domain/models";

export interface SharedCapture extends CaptureDraft {
  accountId?: string;
  memberId?: string;
  imagePath?: string;
}
export interface NoteCapturePlugin {
  addListener(
    event: "recordingStopped",
    listener: (data: { audioId: string }) => void,
  ): Promise<PluginListenerHandle>;
  syncSession(options: { accountId: string; memberId: string }): Promise<void>;
  clearSession(): Promise<void>;
  clipboardStatus(): Promise<{ hasText: boolean; change: number }>;
  readClipboard(): Promise<{ text: string; change: number }>;
  pending(options: {
    accountId: string;
    memberId: string;
  }): Promise<{ items: SharedCapture[] }>;
  acknowledge(options: {
    id: string;
    accountId: string;
    memberId: string;
  }): Promise<void>;
  claim(options: {
    id: string;
    accountId: string;
    memberId: string;
  }): Promise<void>;
  saveImport(options: {
    id: string;
    accountId: string;
    memberId: string;
    title: string;
    thoughts: string;
  }): Promise<{ note: Note }>;
  previewImport(options: {
    id: string;
    accountId: string;
    memberId: string;
  }): Promise<{ dataUrl: string }>;
  copyImport(options: {
    id: string;
    accountId: string;
    memberId: string;
    newId: string;
  }): Promise<void>;
  startRecording(options: { owner: string; id: string }): Promise<void>;
  stopRecording(): Promise<{ audioId: string }>;
  recordings(options: { owner: string }): Promise<{ ids: string[] }>;
  transcribe(options: {
    owner: string;
    audioId: string;
  }): Promise<{ text: string }>;
  removeRecording(options: { owner: string; audioId: string }): Promise<void>;
}
export const iosCapture = registerPlugin<NoteCapturePlugin>("NoteCapture");
export const isIosCapture = () => Capacitor.getPlatform() === "ios";
