param(
    [ValidateSet('requests:read', 'requests:write', 'requests:read requests:write')]
    [string]$Scope = 'requests:read requests:write',
    [string]$Secret = $env:JWT_SECRET,
    [string]$Issuer = $env:JWT_ISSUER,
    [string]$Audience = $env:JWT_AUDIENCE,
    [ValidateNotNullOrEmpty()]
    [ValidateLength(1, 200)]
    [string]$Subject = 'development-client',
    [ValidateRange(1, 60)]
    [int]$ExpirationMinutes = 5
)

# Auxiliar local; não substitui um servidor de autorização OAuth 2.0.
if ([string]::IsNullOrWhiteSpace($Secret)) {
    $Secret = 'dev-only-secret-change-me-0123456789abcdef'
}
if ([string]::IsNullOrWhiteSpace($Issuer)) { $Issuer = 'https://auth.local.test' }
if ([string]::IsNullOrWhiteSpace($Audience)) { $Audience = 'product-activation-service' }
if ([Text.Encoding]::UTF8.GetByteCount($Secret) -lt 32) {
    throw 'O segredo HS256 deve ter pelo menos 32 bytes.'
}

function ConvertTo-Base64Url([byte[]]$Data) {
    [Convert]::ToBase64String($Data).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$tokenHeader = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
$issuedAt = [DateTimeOffset]::UtcNow
$tokenClaims = @{
    sub = $Subject
    iss = $Issuer
    aud = $Audience
    iat = $issuedAt.ToUnixTimeSeconds()
    exp = $issuedAt.AddMinutes($ExpirationMinutes).ToUnixTimeSeconds()
    scope = $Scope
} | ConvertTo-Json -Compress
$tokenPayload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($tokenClaims))
$tokenContent = "$tokenHeader.$tokenPayload"
$tokenSigner = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
try {
    $tokenSignature = ConvertTo-Base64Url ($tokenSigner.ComputeHash([Text.Encoding]::UTF8.GetBytes($tokenContent)))
    "$tokenContent.$tokenSignature"
} finally {
    $tokenSigner.Dispose()
}
