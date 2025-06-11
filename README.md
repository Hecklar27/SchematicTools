# SchematicTools

A Minecraft Fabric mod that adds useful tools for working with Litematica schematics.

## Commands

All commands are prefixed with `/tools`:

### 1. Convert Command
**Usage:** `/tools convert <path> [recursive]`

Converts vanilla structure (.nbt) files and Sponge schematics to Litematica format (.litematic).

- **path**: Path to the file or directory to convert (supports tab completion)
- **recursive**: Optional boolean flag to recursively convert all files in subdirectories

**Features:**
- Automatically detects vanilla structure and Sponge schematic formats
- Preserves directory structure when converting
- Provides detailed feedback on conversion success/failure
- Tab completion for file paths

**Examples:**
- `/tools convert mystructure.nbt` - Convert a single file
- `/tools convert structures/ true` - Recursively convert all files in the structures folder

### 2. Materials Calculator Command
**Usage:** `/tools materials <directory>`

Calculates the total materials required for all schematics in a directory and generates a detailed report.

**Features:**
- Analyzes all .litematic files in the specified directory recursively
- Calculates material counts in blocks, stacks, and shulker boxes
- Generates a comprehensive text report with:
  - Total materials summary across all schematics
  - Per-schematic material breakdowns
  - Clickable link to open the generated report
- Provides immediate feedback for high-count materials in chat

**Additional Commands:**
- `/tools materials open-last` - Opens the most recently generated materials report

**Example:**
- `/tools materials mybuilds/` - Calculate materials for all schematics in the mybuilds folder

### 3. Schematic Beam Renderer Command
**Usage:** `/tools render beams <toggle|refresh>`

Renders visual beams above incomplete blocks in the currently selected schematic placement.

**Subcommands:**
- `toggle` - Enable/disable the beam rendering system
- `refresh` - Manually refresh and recalculate incomplete blocks

**Features:**
- Shows beams only for the topmost incomplete block in each column
- Automatically updates beams as you place blocks (within 10 block radius)
- ignores block state properties
- Works with the currently selected Litematica schematic placement

## Requirements

- Minecraft 1.21.4
- Fabric Loader
- Litematica mod
