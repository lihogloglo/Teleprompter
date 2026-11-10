"""Per-language bench content: TTS voices, narration around the command, the
accepted command phrases (each said in several styles), and near-miss narration."""

LANGS = {
    "fr": dict(
        # fr_FR-gilles-low is excluded: its phoneme table lacks the nasal tilde,
        # so "commande" comes out as "commade".
        voices=[("fr_FR-siwis-medium", 0), ("fr_FR-upmc-medium", 0), ("fr_FR-upmc-medium", 1),
                ("fr_FR-tom-medium", 0), ("fr_FR-miro-high", 0)],
        lead="Et c'est exactement à ce moment-là que tout a basculé pour nous.",
        tail="Alors reprenons depuis le début de cette histoire.",
        commands={
            "commande-recul": {"plain": ["Commande recul."], "comma": ["Commande, recul !"],
                               "pause": ["Commande.", "Recul."]},
            "retour-retour": {"plain": ["Retour retour."], "comma": ["Retour, retour !"],
                              "pause": ["Retour.", "Retour."]},
        },
        neg=[
            "Comment reculer sans perdre la face devant ses clients ?",
            "J'ai passé commande hier soir, et la commande est arrivée ce matin.",
            "Avec le recul, je pense que c'était une erreur de débutant.",
            "Le commandant a donné l'ordre de reculer avant la nuit.",
            "Comment de temps faut-il pour apprendre à coder un jeu vidéo ?",
            "La commande reçue était incomplète, alors on a recommencé.",
            "On ne recule devant rien, c'est comme ça qu'on avance.",
            "Comment le recul change notre façon de voir les choses.",
            "Au retour, on a pris le train, puis un aller-retour à Lyon.",
            "Le retour du héros, c'est le retour de l'espoir.",
            "À votre tour, retournez la carte et faites demi-tour.",
            "Retour à la case départ : tout est à refaire.",
        ],
        candidates=["Commande recul.", "Retour, retour.", "Marche arrière.", "Page précédente.",
                    "Remonte, remonte."],
    ),
    "en": dict(
        voices=[("en_US-amy-medium", 0), ("en_US-lessac-medium", 0), ("en_US-ryan-medium", 0),
                ("en_GB-alan-medium", 0), ("en_GB-jenny_dioco-medium", 0),
                ("en_US-libritts_r-medium", 0), ("en_US-libritts_r-medium", 100)],
        lead="And that is exactly the moment when everything changed for us.",
        tail="So let's start again from the beginning of the story.",
        commands={
            "command-rewind": {"plain": ["Command rewind."], "comma": ["Command, rewind!"],
                               "pause": ["Command.", "Rewind."]},
            "go-back-go-back": {"plain": ["Go back go back."], "comma": ["Go back, go back!"],
                                "pause": ["Go back.", "Go back."]},
        },
        neg=[
            "He commanded the crew to rewind the tape before lunch.",
            "Let's go back to the beginning, back to where it started.",
            "Back in the day, we rewound cassettes with a pencil.",
            "Command the room, then step back and let them talk.",
            "We had back to back meetings all week.",
            "Rewinding the clock is not an option for any of us.",
            "She went back and forth between the two offers.",
            "The command line tool can rewind the log to any point.",
            "Come on, rewind that last part for me.",
            "Come back, go back, it doesn't matter where you stand.",
            "Go back home. Back then, nobody knew.",
            "We kept going back and forth, back and forth.",
        ],
        candidates=["Command rewind.", "Rewind, rewind.", "Back, back.", "Go back, go back.",
                    "Back up, back up.", "Previous page."],
    ),
}
