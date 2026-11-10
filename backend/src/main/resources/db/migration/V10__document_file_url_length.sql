-- COS 带签名的临时访问 URL 含 response-content-type / response-content-disposition
-- 等覆盖参数后超过 512 字符，加宽 file_url 以免写入截断报错。
ALTER TABLE documents
  MODIFY COLUMN file_url VARCHAR(1024) NULL;
