package com.aibot.purchase.common;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * 普通二维码生成工具：把任意文本编码为 PNG 字节流（供小程序内 wx.scanCode 扫描）。
 * 注意：小程序码（wxacode）只能由微信相机扫，wx.scanCode 无法识别，故登录扫码需额外渲染一张普通二维码。
 */
public final class QrCodeUtil {
  private QrCodeUtil() {
  }

  public static byte[] generatePng(String text, int size) throws WriterException, IOException {
    Map<EncodeHintType, Object> hints = Map.of(
        EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN, 1,
        EncodeHintType.CHARACTER_SET, "UTF-8");
    BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    MatrixToImageWriter.writeToStream(matrix, "PNG", out);
    return out.toByteArray();
  }
}
