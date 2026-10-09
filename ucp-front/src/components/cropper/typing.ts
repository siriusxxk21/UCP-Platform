import type Cropper from 'cropperjs'

export interface ApiFunParams {
  file: Blob
  filename: string
  name: string
}

export interface CropendResult {
  imgBase64: string
  imgInfo: Cropper.Data
}

export interface CropperProps {
  src?: string
  alt?: string
  circled?: boolean
  realTimePreview?: boolean
  height?: number | string
  crossorigin?: '' | 'anonymous' | 'use-credentials' | undefined
  options?: Cropper.Options
}

export interface CropperAvatarProps {
  width?: number | string
  value?: string
  showBtn?: boolean
  btnText?: string
  uploadApi?: (params: ApiFunParams) => Promise<any>
  size?: number
}

export interface CropperModalProps {
  open?: boolean
  circled?: boolean
  uploadApi?: (params: ApiFunParams) => Promise<any>
  src?: string
  size?: number
}

export const defaultOptions: Cropper.Options = {
  aspectRatio: 1,
  zoomable: true,
  zoomOnTouch: true,
  zoomOnWheel: true,
  cropBoxMovable: true,
  cropBoxResizable: true,
  toggleDragModeOnDblclick: true,
  autoCrop: true,
  background: true,
  highlight: true,
  center: true,
  responsive: true,
  restore: true,
  checkCrossOrigin: false,
  checkOrientation: true,
  scalable: true,
  modal: true,
  guides: true,
  movable: true,
  rotatable: true,
  viewMode: 1
}

export type CropperType = Cropper
