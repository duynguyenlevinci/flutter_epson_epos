import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:epson_epos/charset/charset.dart';
import 'package:epson_epos/epson_epos.dart';
import 'package:esc_pos_utils_plus/esc_pos_utils_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:logger/logger.dart';
import 'package:permission_handler/permission_handler.dart';

final logger = Logger();

void main() {
  runApp(MyApp());
}

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  List<EpsonPrinterModel> printers = [];

  // Khoá để truy cập RenderRepaintBoundary của UI hoá đơn mẫu
  final GlobalKey _receiptKey = GlobalKey();

  // Khổ giấy 80mm tương đương ~576 dot (203 dpi). Dùng đúng giá trị này để
  // ảnh chụp ra fit khổ giấy mà không bị scale ở phía máy in.
  static const double _receiptWidthPx = 576;

  // Define an encode to support print the specified language
  Uint8List useEncode(String text) {
    return tcvn.encode(text);
  }

  @override
  void initState() {
    super.initState();
    initPlatformState();
  }

  // Platform messages are asynchronous, so we initialize in an async method.
  Future<void> initPlatformState() async {
    if (!mounted) return;

    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        appBar: AppBar(title: const Text('EPSON ePOS')),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.start,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ElevatedButton(
                  onPressed: () => onDiscovery(EpsonEPOSPortType.TCP),
                  child: Text('Discovery TCP'),
                ),
                // ElevatedButton(
                //     onPressed: () => onDiscovery(EpsonEPOSPortType.USB),
                //     child: Text('Discovery USB')),
                ElevatedButton(
                  onPressed: () => onDiscovery(EpsonEPOSPortType.BLUETOOTH),
                  child: Text('Discovery Bluetooth'),
                ),
                // ElevatedButton(
                //     onPressed: () => onDiscovery(EpsonEPOSPortType.ALL),
                ElevatedButton(
                  onPressed: () => onBleRequestPermission(),
                  child: Text('Request runtime permission'),
                ), //     child: Text('Discovery All')),
                ListView.builder(
                  itemBuilder: (BuildContext context, int index) {
                    final printer = printers[index];
                    return Column(
                      children: [
                        Text('${printer.model} | ${printer.series}'),
                        Text('${printer.ipAddress}'),
                        Wrap(
                          spacing: 8,
                          children: [
                            TextButton(
                              onPressed: () {
                                //onSetPrinterSetting(printer);
                                onPrintTest(printer);
                              },
                              child: Text('Print Test'),
                            ),
                            TextButton(
                              onPressed: () {
                                onPrintTest(printer);
                              },
                              child: Text('Print Raw Text'),
                            ),
                            TextButton(
                              onPressed: () => onPrintImage(printer),
                              child: Text('Print Image'),
                            ),
                          ],
                        ),
                      ],
                    );
                  },
                  itemCount: printers.length,
                  primary: false,
                  shrinkWrap: true,
                  physics: NeverScrollableScrollPhysics(),
                ),
                const SizedBox(height: 16),
                const Text(
                  'Receipt preview (sẽ được in dưới dạng ảnh):',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 8),
                _buildReceiptPreview(),
                const SizedBox(height: 24),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// UI hoá đơn mẫu được bọc trong [RepaintBoundary] để có thể chụp lại
  /// thành ảnh và gửi đến máy in.
  ///
  /// Bên ngoài bọc thêm [FittedBox] để preview luôn vừa màn hình, nhưng UI
  /// thực bên trong vẫn giữ đúng kích thước 576px (khổ giấy 80mm) để khi
  /// chụp ra ảnh sẽ in đúng tỉ lệ.
  Widget _buildReceiptPreview() {
    return Center(
      child: FittedBox(
        fit: BoxFit.scaleDown,
        child: RepaintBoundary(
          key: _receiptKey,
          child: Container(
            width: _receiptWidthPx,
            color: Colors.white,
            padding: const EdgeInsets.all(24),
            child: DefaultTextStyle(
              style: const TextStyle(
                color: Colors.black,
                fontSize: 22,
                height: 1.3,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    'CỬA HÀNG ABC',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 36,
                      fontWeight: FontWeight.bold,
                      color: Colors.black,
                    ),
                  ),
                  const SizedBox(height: 4),
                  const Text(
                    '123 Đường XYZ, Quận 1, TP. HCM',
                    textAlign: TextAlign.center,
                  ),
                  const Text(
                    'Hotline: 0900 000 000',
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'HOÁ ĐƠN BÁN HÀNG',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 26, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 12),
                  const _DashedDivider(),
                  const SizedBox(height: 8),
                  _kv('Mã HĐ:', 'HD-2026-0001'),
                  _kv('Ngày:', '25/05/2026 12:00'),
                  _kv('Thu ngân:', 'Nguyễn Văn A'),
                  const SizedBox(height: 8),
                  const _DashedDivider(),
                  const SizedBox(height: 8),
                  const Row(
                    children: [
                      Expanded(
                        flex: 5,
                        child: Text(
                          'Sản phẩm',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                      ),
                      Expanded(
                        flex: 1,
                        child: Text(
                          'SL',
                          textAlign: TextAlign.center,
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                      ),
                      Expanded(
                        flex: 3,
                        child: Text(
                          'Thành tiền',
                          textAlign: TextAlign.right,
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  _item('Cà phê sữa đá', 2, '60.000'),
                  _item('Bánh mì thịt nướng', 1, '35.000'),
                  _item('Trà đào cam sả', 1, '45.000'),
                  const SizedBox(height: 8),
                  const _DashedDivider(),
                  const SizedBox(height: 8),
                  _kv('Tạm tính:', '140.000', bold: true),
                  _kv('VAT (10%):', '14.000'),
                  _kv('TỔNG CỘNG:', '154.000 đ', bold: true, fontSize: 28),
                  const SizedBox(height: 12),
                  const _DashedDivider(),
                  const SizedBox(height: 12),
                  const Text(
                    'Cảm ơn quý khách & hẹn gặp lại!',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontStyle: FontStyle.italic),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _kv(
    String label,
    String value, {
    bool bold = false,
    double? fontSize,
  }) {
    final style = TextStyle(
      fontWeight: bold ? FontWeight.bold : FontWeight.normal,
      fontSize: fontSize,
      color: Colors.black,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(child: Text(label, style: style)),
          Text(value, style: style),
        ],
      ),
    );
  }

  Widget _item(String name, int qty, String total) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(flex: 5, child: Text(name)),
          Expanded(flex: 1, child: Text('$qty', textAlign: TextAlign.center)),
          Expanded(flex: 3, child: Text(total, textAlign: TextAlign.right)),
        ],
      ),
    );
  }

  /// Chụp UI mẫu trong [RepaintBoundary] -> trả về [base64] PNG kèm kích thước.
  Future<({String base64, int width, int height})?>
  _captureReceiptImage() async {
    try {
      final boundary =
          _receiptKey.currentContext?.findRenderObject()
              as RenderRepaintBoundary?;
      if (boundary == null) {
        logger.e('RepaintBoundary chưa render');
        return null;
      }

      // Chờ một frame nếu boundary vẫn đang trong trạng thái cần paint lại,
      // tránh lỗi "Failed assertion: 'debugNeedsPaint'".
      if (boundary.debugNeedsPaint) {
        await Future.delayed(const Duration(milliseconds: 20));
      }

      final image = await boundary.toImage(pixelRatio: 1.0);
      final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
      if (byteData == null) return null;

      final pngBytes = byteData.buffer.asUint8List();
      final base64String = base64Encode(pngBytes);

      return (base64: base64String, width: image.width, height: image.height);
    } catch (e, st) {
      logger.e('Capture receipt error: $e\n$st');
      return null;
    }
  }

  /// In ảnh chụp được từ UI mẫu.
  Future<void> onPrintImage(EpsonPrinterModel printer) async {
    final captured = await _captureReceiptImage();
    if (captured == null) {
      logger.e('Không chụp được UI mẫu');
      return;
    }

    final command = EpsonEPOSCommand();
    final List<Map<String, dynamic>> commands = [];
    commands.add(command.addTextAlign(EpsonEPOSTextAlign.CENTER));
    commands.add(
      command.appendBitmap(
        captured.base64,
        captured.width,
        captured.height,
        0,
        0,
      ),
    );
    commands.add(command.addFeedLine(2));
    commands.add(command.addCut(EpsonEPOSCut.CUT_FEED));

    try {
      final response = await EpsonEPOS.onPrint(printer, commands);
      logger.d(response.toString());
    } catch (e) {
      logger.e('Print image error: $e');
    }
  }

  onDiscovery(EpsonEPOSPortType type) async {
    try {
      List<EpsonPrinterModel>? data = await EpsonEPOS.onDiscovery(type: type);
      logger.d('Did discover ${data?.length}');
      if (data != null && data.length > 0) {
        data.forEach((element) {
          logger.d(element.toJson());
        });
        setState(() {
          printers = data;
        });
      } else {
        setState(() {
          printers = [];
        });
      }
    } catch (e) {
      logger.e("Error: " + e.toString());
    }
  }

  void onSetPrinterSetting(EpsonPrinterModel printer) async {
    try {
      await EpsonEPOS.setPrinterSetting(printer, paperWidth: 80);
    } catch (e) {
      logger.e("Error: " + e.toString());
    }
  }

  Future<List<int>> _customEscPos() async {
    final profile = await CapabilityProfile.load();
    final generator = Generator(PaperSize.mm80, profile);
    List<int> bytes = [];
    generator.setGlobalCodeTable('TCVN-3-1');

    // bytes += generator.text(
    //     'Regular: aA bB cC dD eE fF gG hH iI jJ kK lL mM nN oO pP qQ rR sS tT uU vV wW xX yY zZ');
    // bytes += generator.text('Special 1: àÀ èÈ éÉ ûÛ üÜ çÇ ôÔ',
    //     styles: const PosStyles(codeTable: 'CP1252'));
    // bytes += generator.text('Special 2: blåbærgrød',
    //     styles: const PosStyles(codeTable: 'CP1252'));

    bytes += generator.textEncoded(
      useEncode('Thoát nghe tim đập rộn ràng Cất lên tiếng,'),
      styles: const PosStyles(bold: true),
    );
    // bytes +=
    //     generator.text('Reverse text', styles: const PosStyles(reverse: true));
    // bytes += generator.text('Underlined text',
    //     styles: const PosStyles(underline: true), linesAfter: 1);
    // bytes += generator.text('Align left',
    //     styles: const PosStyles(align: PosAlign.left));

    bytes += generator.setStyles(const PosStyles(align: PosAlign.center));
    bytes += generator.textEncoded(useEncode('hồ Đây Than Thở ngất đây'));

    // bytes += generator.text('Align right',
    //     styles: const PosStyles(align: PosAlign.right), linesAfter: 1);
    // bytes += generator.qrcode('Barcode by escpos',
    //     size: QRSize.Size4, cor: QRCorrection.H);
    // bytes += generator.feed(2);
    bytes += generator.barcode(Barcode.code128('barcodeData'.split('')));
    // bytes += generator.row([
    //   PosColumn(
    //     text: 'col3',
    //     width: 3,
    //     styles: const PosStyles(align: PosAlign.center, underline: true),
    //   ),
    //   PosColumn(
    //     text: 'col6',
    //     width: 6,
    //     styles: const PosStyles(align: PosAlign.center, underline: true),
    //   ),
    //   PosColumn(
    //     text: 'col3',
    //     width: 3,
    //     styles: const PosStyles(align: PosAlign.center, underline: true),
    //   ),
    // ]);

    // bytes += generator.text('Text size 200%',
    //     styles: const PosStyles(
    //       height: PosTextSize.size2,
    //       width: PosTextSize.size2,
    //     ));
    String base64String = base64Encode(bytes);

    bytes += generator.reset();

    return bytes;
  }

  void onPrintRaw(EpsonPrinterModel printer) async {
    EpsonEPOSCommand command = EpsonEPOSCommand();
    List<Map<String, dynamic>> commands = [];
    commands.add(command.addTextAlign(EpsonEPOSTextAlign.LEFT));
    commands.add(command.addTextFont(EpsonEPOSFont.FONT_B));
    // commands.add(command.addFeedLine(1));
    commands.add(command.addTextStyle(bold: true));
    commands.add(
      command.append('Đây bước chân kẻ phong trần Lang thang cõi\n'),
    );
    commands.add(command.addTextStyle(bold: false));
    // commands.add(command.append('ÀẢÃÁẠẶẬÈẺẼÉẸỆÌỈĨÍỊÒỎÕÓỌỘỜỞỠỚỢÙỦŨ ĂÂÊÔƠƯĐ\n'));
    commands.add(command.rawData(Uint8List.fromList(await _customEscPos())));
    commands.add(command.addFeedLine(1));
    commands.add(command.addCut(EpsonEPOSCut.CUT_FEED));
  }

  void onPrintTest(EpsonPrinterModel printer) async {
    EpsonEPOSCommand command = EpsonEPOSCommand();
    List<Map<String, dynamic>> commands = [];
    commands.add(command.addTextAlign(EpsonEPOSTextAlign.LEFT));
    commands.add(command.addTextFont(EpsonEPOSFont.FONT_B));
    // commands.add(command.addFeedLine(1));
    commands.add(command.addTextStyle(bold: true));
    commands.add(
      command.append('Đây bước chân kẻ phong trần Lang thang cõi\n'),
    );
    commands.add(command.addTextStyle(bold: false));
    // commands.add(command.append('ÀẢÃÁẠẶẬÈẺẼÉẸỆÌỈĨÍỊÒỎÕÓỌỘỜỞỠỚỢÙỦŨ ĂÂÊÔƠƯĐ\n'));
    // commands.add(command.rawData(Uint8List.fromList(await _customEscPos())));
    commands.add(command.addFeedLine(1));
    commands.add(command.addTextAlign(EpsonEPOSTextAlign.CENTER));
    commands.add(
      command.addBarcode(
        barcode: '0000081002345',
        type: Epos2Barcode.EPOS2_BARCODE_EAN13,
        position: Epos2Hri.EPOS2_HRI_ABOVE,
        font: EpsonEPOSFont.FONT_B,
      ),
    );
    commands.add(command.addCut(EpsonEPOSCut.CUT_FEED));
    final response = await EpsonEPOS.onPrint(printer, commands);
    logger.d(response.toString());
  }

  void onBleRequestPermission() async {
    Map<Permission, PermissionStatus> statuses = await [
      Permission.location,
      Permission.bluetooth,
      Permission.bluetoothConnect,
      Permission.bluetoothScan,
    ].request();
    logger.d(statuses[Permission.bluetooth]);
  }
}

/// Đường phân cách nét đứt cho UI hoá đơn.
class _DashedDivider extends StatelessWidget {
  const _DashedDivider();

  static const double _height = 1;
  static const double _dashWidth = 6;
  static const double _dashGap = 4;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final dashCount = (constraints.maxWidth / (_dashWidth + _dashGap))
            .floor();
        return Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: List.generate(dashCount, (_) {
            return const SizedBox(
              width: _dashWidth,
              height: _height,
              child: DecoratedBox(
                decoration: BoxDecoration(color: Colors.black),
              ),
            );
          }),
        );
      },
    );
  }
}
