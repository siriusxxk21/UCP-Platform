declare module 'bpmn-auto-layout' {
  /** 1.x 返回新的 XML 字符串，原始部署内容不会被修改。 */
  export function layoutProcess(xml: string): Promise<string>
}
