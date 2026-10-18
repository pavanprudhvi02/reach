# Terminal recording of scripts/demo.sh, rendered with VHS (github.com/charmbracelet/vhs).
# Run it against a stack that is already up: vhs demo/demo.tape
Output demo/reach-failover.gif

Set Shell "bash"
Set FontSize 15
Set Width 1180
Set Height 940
Set Padding 18
Set Theme "Catppuccin Mocha"
Set TypingSpeed 45ms

Hide
Type "export PS1='$ ' && clear"
Enter
Show

Sleep 800ms
Type "docker compose ps --format '{{.Service}}  {{.Status}}'"
Sleep 400ms
Enter
Sleep 2s
Type "scripts/demo.sh"
Sleep 400ms
Enter
Wait+Screen@240s /worker-1 is back in the pool/
Sleep 4s
