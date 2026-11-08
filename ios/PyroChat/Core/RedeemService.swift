// ACCESS_KEY_UI (ios/project.yml): access keys exist in Debug only until they
// unlock something again. A Release binary carries none of this.
#if ACCESS_KEY_UI
import Foundation
import _CryptoExtras

/// Turns a redeem code into 31 anonymous daily access tokens (RFC 9474
/// RSABSSA-SHA384-PSS-Deterministic):
///
/// 1. build one token per subscription day: epochDay(8B BE) ‖ nonce(32B)
/// 2. blind each against the billing server's RSA key
/// 3. POST code + blinded values — billing signs WITHOUT seeing the tokens
/// 4. unblind; store (token, signature) pairs locally
///
/// The relay later verifies signatures with the same public key but can
/// never link a token to this redemption, the code, or the payment.
enum RedeemService {
    enum RedeemError: LocalizedError {
        case badServer, badCode, network
        var errorDescription: String? {
            switch self {
            case .badServer: return "billing server unreachable"
            case .badCode: return "invalid or already-used access key"
            case .network: return "network error"
            }
        }
    }

    /// `session` MUST be a proxied session from RelayClient.makeSession —
    /// redemption over the real IP would let billing correlate (IP, code,
    /// time) with the relay login and break token unlinkability.
    static func redeem(code: String, billingURL: URL,
                       session: URLSession) async throws -> [AccessToken] {
        // fetch the billing public key
        guard let (pkData, _) = try? await session.data(
                from: billingURL.appendingPathComponent("api/billing-pubkey")),
              let pkJson = try? JSONDecoder().decode([String: String].self, from: pkData),
              let pem = pkJson["pem"] else { throw RedeemError.badServer }

        let publicKey = try _RSA.BlindSigning.PublicKey<SHA384>(
            pemRepresentation: pem, parameters: .RSABSSA_SHA384_PSS_Deterministic)

        // one token per day, starting today (UTC epoch days)
        let today = Int64(Date().timeIntervalSince1970 / 86_400)
        var tokens: [Data] = []
        var prepared: [_RSA.BlindSigning.PreparedMessage] = []
        var inverses: [_RSA.BlindSigning.BlindingInverse] = []
        var blindedB64: [String] = []

        for day in 0..<31 {
            var token = Data()
            var epochBE = (today + Int64(day)).bigEndian
            token.append(Data(bytes: &epochBE, count: 8))
            var nonce = Data(count: 32)
            _ = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
            token.append(nonce)

            let prep = publicKey.prepare(token)
            let blind = try publicKey.blind(prep)
            tokens.append(token)
            prepared.append(prep)
            inverses.append(blind.inverse)
            blindedB64.append(blind.blindedMessage.base64EncodedString())
        }

        // submit to billing
        var req = RelayClient.fixed(URLRequest(url: billingURL.appendingPathComponent("api/redeem")))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(["code": AnyEncodable(code),
                                                 "blinded": AnyEncodable(blindedB64)])
        guard let (data, resp) = try? await session.data(for: req),
              let http = resp as? HTTPURLResponse else { throw RedeemError.network }
        guard http.statusCode == 200 else { throw RedeemError.badCode }
        guard let reply = try? JSONDecoder().decode(RedeemReply.self, from: data),
              reply.signatures.count == 31 else { throw RedeemError.badServer }

        // unblind + verify each signature locally before trusting it
        var out: [AccessToken] = []
        for i in 0..<31 {
            guard let sigData = Data(base64Encoded: reply.signatures[i]) else {
                throw RedeemError.badServer
            }
            let blindSig = _RSA.BlindSigning.BlindSignature(rawRepresentation: sigData)
            let final = try publicKey.finalize(blindSig, for: prepared[i],
                                               blindingInverse: inverses[i])
            guard publicKey.isValidSignature(final, for: prepared[i]) else {
                throw RedeemError.badServer
            }
            let epochDay = tokens[i].prefix(8).reduce(Int64(0)) { ($0 << 8) | Int64($1) }
            out.append(AccessToken(epochDay: epochDay, token: tokens[i],
                                   signature: final.rawRepresentation))
        }
        return out
    }

    private struct RedeemReply: Decodable {
        var signatures: [String]
        var days: Int
    }

    /// Minimal type-erasing encoder for the mixed-value request body.
    private struct AnyEncodable: Encodable {
        let value: Encodable
        init(_ value: Encodable) { self.value = value }
        func encode(to encoder: Encoder) throws { try value.encode(to: encoder) }
    }
}
#endif
