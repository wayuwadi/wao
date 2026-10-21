import { common, createLowlight } from 'lowlight'

export const codeLowlight = createLowlight(common)
export const codeLanguages = ['plaintext', 'sql', 'python', 'javascript', 'typescript', 'java', 'json', 'yaml', 'bash', 'html', 'css', 'markdown', ...Object.keys(common)].filter((value, index, values) => values.indexOf(value) === index)
