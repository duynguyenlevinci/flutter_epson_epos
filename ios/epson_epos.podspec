#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint epson_epos.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'epson_epos'
  s.version          = '1.1.0'
  s.summary          = 'Epson ePOS printer plugin.'
  s.description      = <<-DESC
A Flutter plugin to discover Epson ePOS printers and send print commands.
                       DESC
  s.homepage         = 'https://github.com/mthuong/flutter_epson_epos'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Tom' => 'mthuong.github.io' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '13.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386',
    'SWIFT_VERSION' => '5.0'
  }
  s.swift_version = '5.0'

  # libepos2.xcframework
  s.preserve_paths = 'libepos2.xcframework/**/*'
  s.vendored_frameworks = 'libepos2.xcframework'

  s.libraries = 'xml2'
  s.frameworks = 'CoreBluetooth', 'ExternalAccessory'

  # Localization
  s.resource = 'Localizations/ePOS2Localizable.strings'
end
