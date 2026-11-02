package macro

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/local/linux-deck-daemon/internal/auth"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

// ButtonConfig represents a single Deck button or widget matching profile_schema.json.
type ButtonConfig struct {
	ButtonID            string            `json:"button_id"`
	Type                string            `json:"type"`
	Row                 int               `json:"row"`
	Col                 int               `json:"col"`
	RowSpan             int               `json:"row_span,omitempty"`
	ColSpan             int               `json:"col_span,omitempty"`
	Title               string            `json:"title"`
	Color               string            `json:"color,omitempty"`
	ActiveIcon          string            `json:"active_icon,omitempty"`
	InactiveIcon        string            `json:"inactive_icon,omitempty"`
	Command             string            `json:"command,omitempty"`
	Env                 map[string]string `json:"env,omitempty"`
	WorkingDir          string            `json:"working_dir,omitempty"`
	TimeoutSeconds      int               `json:"timeout_seconds,omitempty"`
	PollCommand         string            `json:"poll_command,omitempty"`
	PollIntervalSeconds int               `json:"poll_interval_seconds,omitempty"`
	KeyCombo            string            `json:"key_combo,omitempty"`
	MediaAction         string            `json:"media_action,omitempty"`
	TelemetryMetric     string            `json:"telemetry_metric,omitempty"`
	TargetFolderID      string            `json:"target_folder_id,omitempty"`
}

// PageConfig represents a single grid page or folder within a Deck profile.
type PageConfig struct {
	PageID   string         `json:"page_id"`
	Title    string         `json:"title"`
	IsFolder bool           `json:"is_folder,omitempty"`
	Rows     int            `json:"rows,omitempty"`
	Cols     int            `json:"cols,omitempty"`
	Buttons  []ButtonConfig `json:"buttons"`
}

// ProfileConfig represents the complete Deck configuration persisted in default.json.
type ProfileConfig struct {
	Version   int          `json:"version,omitempty"`
	ProfileID string       `json:"profile_id"`
	Name      string       `json:"name"`
	Rows      int          `json:"rows"`
	Cols      int          `json:"cols"`
	Pages     []PageConfig `json:"pages"`
}

// ProfileStore manages loading and saving Deck profiles on disk.
type ProfileStore struct {
	mu       sync.RWMutex
	filePath string
	profile  ProfileConfig
}

// NewProfileStore loads ~/.config/deck-daemon/profiles/default.json or creates a rich default profile.
func NewProfileStore(configDir string) (*ProfileStore, error) {
	if configDir == "" {
		var err error
		configDir, err = auth.DefaultConfigDir()
		if err != nil {
			return nil, err
		}
	}

	profilesDir := filepath.Join(configDir, "profiles")
	if err := os.MkdirAll(profilesDir, auth.DirPerm); err != nil {
		return nil, fmt.Errorf("macro profile store mkdir: %w", err)
	}

	filePath := filepath.Join(profilesDir, "default.json")
	store := &ProfileStore{
		filePath: filePath,
	}

	if info, err := os.Stat(filePath); err == nil && !info.IsDir() {
		if err := store.load(); err != nil {
			return nil, err
		}
	} else {
		store.profile = DefaultProfile()
		if err := store.saveLocked(); err != nil {
			return nil, err
		}
	}

	return store, nil
}

// DefaultProfile returns a rich default Deck profile containing terminal shortcuts,
// media controls, screen lock, system commands, and CPU/RAM telemetry widgets.
func DefaultProfile() ProfileConfig {
	return ProfileConfig{
		Version:   1,
		ProfileID: "default",
		Name:      "Linux Workstation Deck",
		Rows:      3,
		Cols:      5,
		Pages: []PageConfig{
			{
				PageID: "main",
				Title:  "Main Control",
				Rows:   3,
				Cols:   5,
				Buttons: []ButtonConfig{
					{
						ButtonID:        "widget-cpu",
						Type:            "TELEMETRY_WIDGET",
						Row:             0,
						Col:             0,
						RowSpan:         1,
						ColSpan:         1,
						Title:           "CPU Load",
						Color:           "#0F172A",
						TelemetryMetric: "CPU",
					},
					{
						ButtonID:        "widget-ram",
						Type:            "TELEMETRY_WIDGET",
						Row:             0,
						Col:             1,
						RowSpan:         1,
						ColSpan:         1,
						Title:           "RAM Usage",
						Color:           "#0F172A",
						TelemetryMetric: "RAM",
					},
					{
						ButtonID:     "shortcut-terminal",
						Type:         "KEY_COMBO",
						Row:          0,
						Col:          2,
						RowSpan:      1,
						ColSpan:      1,
						Title:        "Open Terminal",
						Color:        "#1E293B",
						InactiveIcon: "terminal",
						ActiveIcon:   "terminal_active",
						KeyCombo:     "CTRL+ALT+T",
					},
					{
						ButtonID:       "cmd-lock-screen",
						Type:           "COMMAND",
						Row:            0,
						Col:            3,
						RowSpan:        1,
						ColSpan:        1,
						Title:          "Lock Screen",
						Color:          "#7F1D1D",
						InactiveIcon:   "lock",
						ActiveIcon:     "lock_open",
						Command:        "loginctl lock-session",
						TimeoutSeconds: 10,
					},
					{
						ButtonID:       "folder-sysadmin",
						Type:           "FOLDER",
						Row:            0,
						Col:            4,
						RowSpan:        1,
						ColSpan:        1,
						Title:          "SysAdmin",
						Color:          "#1D4ED8",
						InactiveIcon:   "folder",
						TargetFolderID: "sysadmin",
					},
					{
						ButtonID:     "media-prev",
						Type:         "MEDIA",
						Row:          1,
						Col:          0,
						RowSpan:      1,
						ColSpan:      1,
						Title:        "Previous",
						Color:        "#1E293B",
						InactiveIcon: "skip_previous",
						MediaAction:  "PREVIOUS",
					},
					{
						ButtonID:     "media-play-pause",
						Type:         "MEDIA",
						Row:          1,
						Col:          1,
						RowSpan:      1,
						ColSpan:      1,
						Title:        "Play / Pause",
						Color:        "#065F46",
						InactiveIcon: "play_arrow",
						ActiveIcon:   "pause",
						MediaAction:  "PLAY_PAUSE",
					},
					{
						ButtonID:     "media-next",
						Type:         "MEDIA",
						Row:          1,
						Col:          2,
						RowSpan:      1,
						ColSpan:      1,
						Title:        "Next",
						Color:        "#1E293B",
						InactiveIcon: "skip_next",
						MediaAction:  "NEXT",
					},
					{
						ButtonID:     "media-mute",
						Type:         "MEDIA",
						Row:          1,
						Col:          3,
						RowSpan:      1,
						ColSpan:      1,
						Title:        "Mute Audio",
						Color:        "#334155",
						InactiveIcon: "volume_up",
						ActiveIcon:   "volume_off",
						MediaAction:  "MUTE",
					},
					{
						ButtonID:            "cmd-uptime",
						Type:                "COMMAND",
						Row:                 2,
						Col:                 0,
						RowSpan:             1,
						ColSpan:             2,
						Title:               "System Uptime",
						Color:               "#1E293B",
						InactiveIcon:        "schedule",
						Command:             "uptime -p || uptime",
						TimeoutSeconds:      5,
						PollCommand:         "uptime -p || uptime",
						PollIntervalSeconds: 30,
					},
				},
			},
			{
				PageID:   "sysadmin",
				Title:    "SysAdmin Tools",
				IsFolder: true,
				Rows:     3,
				Cols:     5,
				Buttons: []ButtonConfig{
					{
						ButtonID:       "cmd-disk-usage",
						Type:           "COMMAND",
						Row:            0,
						Col:            0,
						RowSpan:        1,
						ColSpan:        1,
						Title:          "Disk Usage",
						Color:          "#1E293B",
						Command:        "df -h /",
						TimeoutSeconds: 10,
					},
				},
			},
		},
	}
}

// FilePath returns the path to the profile JSON file on disk.
func (s *ProfileStore) FilePath() string {
	return s.filePath
}

// GetProfile returns a deep copy of the active ProfileConfig.
func (s *ProfileStore) GetProfile() ProfileConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()

	raw, _ := json.Marshal(s.profile)
	var out ProfileConfig
	_ = json.Unmarshal(raw, &out)
	return out
}

// SaveProfile validates and persists a new ProfileConfig to disk.
func (s *ProfileStore) SaveProfile(p ProfileConfig) error {
	if err := ValidateProfile(p); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.profile = p
	return s.saveLocked()
}

// FindButton searches all pages in the current profile for a button matching buttonID.
func (s *ProfileStore) FindButton(buttonID string) (ButtonConfig, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for _, page := range s.profile.Pages {
		for _, btn := range page.Buttons {
			if btn.ButtonID == buttonID {
				return btn, true
			}
		}
	}
	return ButtonConfig{}, false
}

// ToProto converts the active ProfileConfig into a DeckProfileSync protobuf message.
func (s *ProfileStore) ToProto() (*pb.DeckProfileSync, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	rawJSON, err := json.Marshal(s.profile)
	if err != nil {
		return nil, fmt.Errorf("macro profile marshal json: %w", err)
	}

	pages := make([]*pb.DeckPageSpec, 0, len(s.profile.Pages))
	for _, p := range s.profile.Pages {
		btns := make([]*pb.DeckButtonSpec, 0, len(p.Buttons))
		for _, b := range p.Buttons {
			rowSpan := b.RowSpan
			if rowSpan <= 0 {
				rowSpan = 1
			}
			colSpan := b.ColSpan
			if colSpan <= 0 {
				colSpan = 1
			}
			btns = append(btns, &pb.DeckButtonSpec{
				ButtonId:        b.ButtonID,
				Type:            parseButtonTypeProto(b.Type),
				Row:             int32(b.Row),
				Col:             int32(b.Col),
				RowSpan:         int32(rowSpan),
				ColSpan:         int32(colSpan),
				Title:           b.Title,
				Color:           b.Color,
				ActiveIcon:      b.ActiveIcon,
				InactiveIcon:    b.InactiveIcon,
				Command:         b.Command,
				Env:             b.Env,
				KeyCombo:        b.KeyCombo,
				MediaAction:     b.MediaAction,
				TelemetryMetric: b.TelemetryMetric,
				TargetFolderId:  b.TargetFolderID,
				TimeoutSeconds:  uint32(max(0, b.TimeoutSeconds)),
			})
		}
		pages = append(pages, &pb.DeckPageSpec{
			PageId:  p.PageID,
			Title:   p.Title,
			Rows:    int32(p.Rows),
			Cols:    int32(p.Cols),
			Buttons: btns,
		})
	}

	return &pb.DeckProfileSync{
		ProfileId: s.profile.ProfileID,
		Name:      s.profile.Name,
		Rows:      int32(s.profile.Rows),
		Cols:      int32(s.profile.Cols),
		Pages:     pages,
		RawJson:   string(rawJSON),
	}, nil
}

// ValidateProfile checks that required fields from profile_schema.json are valid.
func ValidateProfile(p ProfileConfig) error {
	if strings.TrimSpace(p.ProfileID) == "" {
		return errors.New("macro profile: profile_id is required")
	}
	if strings.TrimSpace(p.Name) == "" {
		return errors.New("macro profile: name is required")
	}
	if p.Rows < 1 || p.Rows > 12 {
		return fmt.Errorf("macro profile: rows %d out of range [1, 12]", p.Rows)
	}
	if p.Cols < 1 || p.Cols > 16 {
		return fmt.Errorf("macro profile: cols %d out of range [1, 16]", p.Cols)
	}
	if len(p.Pages) == 0 {
		return errors.New("macro profile: at least one page is required")
	}
	for _, page := range p.Pages {
		if strings.TrimSpace(page.PageID) == "" {
			return errors.New("macro profile: page_id is required")
		}
		for _, b := range page.Buttons {
			if strings.TrimSpace(b.ButtonID) == "" {
				return errors.New("macro profile: button_id is required")
			}
			if parseButtonTypeProto(b.Type) == pb.ButtonType_BUTTON_TYPE_UNSPECIFIED {
				return fmt.Errorf("macro profile: invalid button type %q", b.Type)
			}
		}
	}
	return nil
}

func (s *ProfileStore) load() error {
	data, err := os.ReadFile(s.filePath)
	if err != nil {
		return fmt.Errorf("macro profile read %s: %w", s.filePath, err)
	}
	var parsed ProfileConfig
	if err := json.Unmarshal(data, &parsed); err != nil {
		return fmt.Errorf("macro profile parse %s: %w", s.filePath, err)
	}
	if err := ValidateProfile(parsed); err != nil {
		return err
	}
	s.profile = parsed
	return nil
}

func (s *ProfileStore) saveLocked() error {
	data, err := json.MarshalIndent(s.profile, "", "  ")
	if err != nil {
		return fmt.Errorf("macro profile marshal: %w", err)
	}
	return os.WriteFile(s.filePath, data, auth.FilePerm)
}

func parseButtonTypeProto(t string) pb.ButtonType {
	switch strings.ToUpper(strings.TrimSpace(t)) {
	case "COMMAND":
		return pb.ButtonType_COMMAND
	case "KEY_COMBO":
		return pb.ButtonType_KEY_COMBO
	case "MEDIA":
		return pb.ButtonType_MEDIA
	case "TELEMETRY_WIDGET":
		return pb.ButtonType_TELEMETRY_WIDGET
	case "FOLDER":
		return pb.ButtonType_FOLDER
	default:
		return pb.ButtonType_BUTTON_TYPE_UNSPECIFIED
	}
}

