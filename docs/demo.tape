# make demo regenerates docs/demo.gif from this.
Output docs/demo.gif
Set Shell "bash"
Set FontSize 15
Set Width 1180
Set Height 860
Set Padding 16
Set TypingSpeed 35ms
Set Theme { "name": "fieldlog", "background": "#fffbf7", "foreground": "#1f2933", "black": "#1f2933", "red": "#b42318", "green": "#1a7f37", "yellow": "#8d6e4f", "blue": "#2a5d84", "magenta": "#7a4d8c", "cyan": "#2b7a78", "white": "#d0d7de", "brightBlack": "#5f6b76", "brightRed": "#b42318", "brightGreen": "#1a7f37", "brightYellow": "#8d6e4f", "brightBlue": "#2a5d84", "brightMagenta": "#7a4d8c", "brightCyan": "#2b7a78", "brightWhite": "#ffffff", "cursor": "#5b4636", "selection": "#efe6da" }

Hide
Type "export PATH=$PWD/cli/build/install/fieldlog/bin:$PATH PS1='$ ' && clear" Enter
Show

Type "fieldlog demo -o /tmp/merged.csv" Sleep 400ms Enter
Sleep 9s
Type "clear" Enter
Type "strata query examples/site.csv 600 480     # before: nearest hole 120 m away" Sleep 300ms Enter
Sleep 3s
Type "strata query /tmp/merged.csv 600 480       # after: BH41, logged offline and merged" Sleep 300ms Enter
Sleep 6s
