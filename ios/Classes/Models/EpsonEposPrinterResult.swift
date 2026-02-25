//
//  EpsonEposPrinterResult.swift
//  epson_epos
//
//  Created by Thomas on 09/08/2024.
//

import Foundation

public class EpsonEposPrinterResult: NSObject, Codable {
    /// Type of the method
    var type: String
    var success: Bool
    var message: String?
    var code: Int32?
    var content: (any Codable)?
    
    init(type: String, success: Bool, message: String? = nil, code: Int32? = nil, content: (any Codable)? = nil) {
        self.type = type
        self.success = success
        self.message = message
        self.code = code
        self.content = content
    }
    
    enum CodingKeys: String, CodingKey {
        case type
        case success
        case message
        case code
        case content
        case contentType
    }
    
    required public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decode(String.self, forKey: .type)
        success = try container.decode(Bool.self, forKey: .success)
        message = try container.decodeIfPresent(String.self, forKey: .message)
        code = try container.decodeIfPresent(Int32.self, forKey: .code)
        
        if let contentTypeString = try container.decodeIfPresent(String.self, forKey: .contentType),
           let contentType = NSClassFromString(contentTypeString) as? Codable.Type {
            content = try contentType.init(from: try container.superDecoder(forKey: .content))
        }
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(type, forKey: .type)
        try container.encode(success, forKey: .success)
        try container.encode(message, forKey: .message)
        try container.encode(code, forKey: .code)
        
        if let content = content {
            let contentTypeString = String(describing: Swift.type(of: content))
            try container.encode(contentTypeString, forKey: .contentType)
            try content.encode(to: container.superEncoder(forKey: .content))
        }
    }
    
    public func toJSONString() throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .prettyPrinted
        let data = try encoder.encode(self)
        return String(data: data, encoding: .utf8)!
    }
}

/**
 // Create an instance of EpsonEposPrinterResult with PrinterContent
 let content = PrinterContent(message: "Print this message")
 let result = EpsonEposPrinterResult()
 result.type = "Print"
 result.success = true
 result.message = "Operation successful"
 result.content = content

 // Encode to JSON
 let encoder = JSONEncoder()
 encoder.outputFormatting = .prettyPrinted
 let data = try encoder.encode(result)
 print(String(data: data, encoding: .utf8)!)

 // Decode from JSON
 let decoder = JSONDecoder()
 let decodedResult = try decoder.decode(EpsonEposPrinterResult.self, from: data)
 if let decodedContent = decodedResult.content as? PrinterContent {
     print(decodedContent.message)  // "Print this message"
 }
 */
