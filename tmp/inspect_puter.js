async function test() {
    const res = await fetch("https://js.puter.com/v2/");
    const code = await res.text();
    const matches = code.match(/"\/[a-zA-Z0-9_\-\/]+"/g) || [];
    const filtered = Array.from(new Set(matches)).filter(m => 
        m.includes("token") || m.includes("auth") || m.includes("anon") || 
        m.includes("driver") || m.includes("ai") || m.includes("tts")
    );
    console.log("Filtered paths:", filtered);
}
test();
