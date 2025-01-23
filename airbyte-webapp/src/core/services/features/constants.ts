import { FeatureItem } from "./types";

export const defaultOssFeatures = [
  FeatureItem.AllowAutoDetectSchema,
  FeatureItem.AllowUpdateConnectors,
  FeatureItem.AllowUploadCustomImage,
  FeatureItem.EnterpriseUpsell,
];

export const defaultEnterpriseFeatures = [
  ...defaultOssFeatures,
  FeatureItem.AllowAllRBACRoles,
  FeatureItem.ConnectionHistoryGraphs,
  FeatureItem.DiagnosticsExport,
  FeatureItem.DisplayOrganizationUsers,
  FeatureItem.EnterpriseBranding,
  FeatureItem.EnterpriseLicenseChecking,
  FeatureItem.FieldHashing,
  FeatureItem.IndicateGuestUsers,
  FeatureItem.MappingsUI, // Indicates configuration UI is present.  connection.mappingsUI experiment also required to be true.
  FeatureItem.MultiWorkspaceUI,
  FeatureItem.RBAC,
];

export const defaultCloudFeatures = [
  FeatureItem.AllowAutoDetectSchema,
  FeatureItem.AllowChangeDataGeographies,
  FeatureItem.AllowDBTCloudIntegration,
  FeatureItem.CloudForTeamsUpsell,
  FeatureItem.ConnectionHistoryGraphs,
  FeatureItem.ConnectorBreakingChangeDeadlines,
  FeatureItem.EmailNotifications,
  FeatureItem.ExternalInvitations,
  FeatureItem.MultiWorkspaceUI,
  FeatureItem.RBAC,
  FeatureItem.RestrictAdminInForeignWorkspace,
  FeatureItem.ShowInviteUsersHint,
  FeatureItem.FieldHashing, // also governed by connection.hashingUI experiment
];
