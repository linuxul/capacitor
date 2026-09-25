import type { CapFormDataEntry } from '../definitions-internal';

// Base64 of a Blob (File included). Blob.arrayBuffer replaces FileReader.readAsBinaryString, which
// is deprecated. The bytes are turned into a binary string in chunks so large bodies do not exceed
// the argument limit of String.fromCharCode.
export const readBlobAsBase64 = async (blob: Blob): Promise<string> => {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
};

const convertFormData = async (formData: FormData): Promise<any> => {
  const newFormData: CapFormDataEntry[] = [];
  for (const pair of formData.entries()) {
    const [key, value] = pair;
    if (value instanceof File) {
      const base64File = await readBlobAsBase64(value);
      newFormData.push({
        key,
        value: base64File,
        type: 'base64File',
        contentType: value.type,
        fileName: value.name,
      });
    } else {
      newFormData.push({ key, value, type: 'string' });
    }
  }

  return newFormData;
};

export const convertBody = async (
  body: Document | XMLHttpRequestBodyInit | ReadableStream<any> | undefined,
  contentType?: string,
): Promise<any> => {
  // Other binary views and plain buffers take the same path as a Uint8Array instead of being sent as JSON.
  if (body instanceof ArrayBuffer) {
    body = new Uint8Array(body);
  } else if (ArrayBuffer.isView(body) && !(body instanceof Uint8Array)) {
    body = new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
  }

  if ((typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) || body instanceof Uint8Array) {
    let encodedData;
    if (body instanceof ReadableStream) {
      const reader = body.getReader();
      const chunks: any[] = [];
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value);
      }
      const concatenated = new Uint8Array(chunks.reduce((acc, chunk) => acc + chunk.length, 0));
      let position = 0;
      for (const chunk of chunks) {
        concatenated.set(chunk, position);
        position += chunk.length;
      }
      encodedData = concatenated;
    } else {
      encodedData = body;
    }

    let data = new TextDecoder().decode(encodedData);
    let type;
    if (contentType === 'application/json') {
      try {
        data = JSON.parse(data);
      } catch (ignored) {
        // ignore
      }
      type = 'json';
    } else if (contentType === 'multipart/form-data') {
      type = 'formData';
    } else if (contentType?.startsWith('image')) {
      type = 'image';
    } else if (contentType === 'application/octet-stream') {
      type = 'binary';
    } else {
      type = 'text';
    }

    return {
      data,
      type,
      headers: { 'Content-Type': contentType || 'application/octet-stream' },
    };
  } else if (body instanceof URLSearchParams) {
    return {
      data: body.toString(),
      type: 'text',
    };
  } else if (body instanceof FormData) {
    return {
      data: await convertFormData(body),
      type: 'formData',
    };
  } else if (typeof Blob !== 'undefined' && body instanceof Blob) {
    // A File, or any other Blob: sent base64 encoded so binary content survives the bridge.
    return {
      data: await readBlobAsBase64(body),
      type: 'file',
      headers: { 'Content-Type': body.type || contentType || 'application/octet-stream' },
    };
  }

  return { data: body, type: 'json' };
};
